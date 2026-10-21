package com.newoether.agora.webui

import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.content.TextContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondOutputStream
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Superficie HTTP del WebUI: el frontend empaquetado más la API de sesión.
 *
 * El navegador es solo un control remoto, así que cada ruta aquí es o un asset estático o una
 * llamada JSON pequeña. Las peticiones cross-site se rechazan tres veces: la cookie de sesión es
 * SameSite=Strict, todo POST debe llevar `application/json` (lo que fuerza un preflight CORS que
 * este server jamás contesta), y un header Origin presente debe coincidir con el Host que usó el
 * navegador.
 */
internal class WebUiServer(
    private val auth: WebUiAuth,
    /** Lee un archivo del frontend empaquetado por su path bajo el asset root, o null si falta. */
    private val readAsset: (String) -> ByteArray?,
    /** Sirve una conexión `/api/sync` firmada: token de login, frames entrantes y un sender de texto. */
    private val syncSession: suspend (String, ReceiveChannel<String>, suspend (String) -> Unit) -> Unit,
    /** Un POST de upload con el token y connectionId de la conexión dueña; 404 cuando no aplica. */
    private val upload: suspend (
        login: String, connectionId: String, seq: Long, name: String, mime: String?,
        forcedType: String?, size: Long?, io.ktor.utils.io.ByteReadChannel,
    ) -> HttpStatusCode = { _, _, _, _, _, _, _, _ -> HttpStatusCode.NotFound },
    /** Un GET de preview con el token y connectionId de la conexión dueña; false cuando no aplica. */
    private val previewAttachment: suspend (
        login: String, connectionId: String, seq: Long, attachmentId: String, kind: String,
        index: Int, consume: suspend (java.io.File, String) -> Unit,
    ) -> Boolean = { _, _, _, _, _, _, _ -> false },
    /** CSS variables del tema actual de la app; vacío conserva los defaults de `style.css`. */
    private val themeCss: () -> String = { "" },
    /** El archivo de fuente de la app servido en [WebUiTheme.FONT_PATH], o null con fuente del sistema. */
    private val readAppFont: () -> ByteArray? = { null },
    /** El archivo de la fuente de código de la app para un nombre de estilo, o null si es desconocido. */
    private val readMonoFont: (String) -> ByteArray? = { null },
    /** True cuando este request llegó por HTTPS: su cookie de sesión se marca Secure. */
    private val secureCookies: (ApplicationCall) -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun install(application: Application) = with(application) {
        install(SecurityHeaders)
        install(WebSockets) {
            pingPeriod = SYNC_PING_PERIOD
            timeout = SYNC_TIMEOUT
            maxFrameSize = MAX_SYNC_FRAME_BYTES
        }
        // Chequeado antes del upgrade: un navegador rechazado recibe un 403 plano, jamás un socket.
        val syncGate = createRouteScopedPlugin("WebUiSyncGate") {
            onCall { call ->
                if (!call.isSameOrigin() || !auth.isValidSession(call.request.cookies[SESSION_COOKIE])) {
                    call.respond(HttpStatusCode.Forbidden)
                }
            }
        }
        routing {
            route("/api/sync") {
                install(syncGate)
                webSocket { serveSync() }
            }
            // Preview de attachments: GET con kind (source/page/frame). El session pinea el
            // artifact canónico bajo filesDir; el stream se revoca si el login muere a medias.
            route("/api/attachments/{connectionId}/{attachmentId}/{kind}/{index}") {
                install(syncGate)
                get {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    val token = call.request.cookies[SESSION_COOKIE] ?: return@get
                    val seq = call.request.queryParameters["seq"]?.toLongOrNull()?.takeIf { it >= 0 }
                    val index = call.parameters["index"]?.toIntOrNull()?.takeIf { it >= 0 }
                    val kind = call.parameters["kind"]?.takeIf { it in setOf("source", "page", "frame") }
                    if (seq == null || index == null || kind == null) return@get call.respond(HttpStatusCode.NotFound)
                    coroutineScope {
                        // Un logout durante el stream corta el request con el token revocado.
                        val requestJob = currentCoroutineContext()[kotlinx.coroutines.Job]!!
                        val revoke = launch { auth.awaitSessionEnd(token); requestJob.cancel() }
                        try {
                            val found = previewAttachment(
                                token, call.parameters["connectionId"].orEmpty(), seq,
                                call.parameters["attachmentId"].orEmpty(), kind, index,
                            ) { file, mime ->
                                if (!auth.isValidSession(token)) throw kotlinx.coroutines.CancellationException("Session revoked")
                                withContext(Dispatchers.IO) {
                                    val size = file.length()
                                    val streamContext = currentCoroutineContext()
                                    val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
                                    // Ktor puede invocar el writer tras retornar respond: el pin de
                                    // sesión vive hasta que el stream termina de asentarse.
                                    call.respondOutputStream(ContentType.parse(mime), contentLength = size) {
                                        try {
                                            withContext(streamContext) {
                                                if (!auth.isValidSession(token)) throw kotlinx.coroutines.CancellationException("Session revoked")
                                                java.io.FileInputStream(file).use { input ->
                                                    if (input.channel.size() != size) throw java.io.IOException("Attachment size changed before response")
                                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                                    var remaining = size
                                                    while (remaining > 0) {
                                                        currentCoroutineContext().ensureActive()
                                                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                                        if (count < 0) throw java.io.EOFException("Attachment ended before its recorded size")
                                                        write(buffer, 0, count)
                                                        remaining -= count
                                                    }
                                                }
                                            }
                                            finished.complete(Unit)
                                        } catch (error: Throwable) {
                                            finished.completeExceptionally(error)
                                            throw error
                                        }
                                    }
                                    finished.await()
                                }
                            }
                            if (!found) call.respond(HttpStatusCode.NotFound)
                        } finally {
                            revoke.cancel()
                        }
                    }
                }
            }
            // Upload de attachments: stream octet-stream acotado; la sesión dueña valida
            // seq/congelamiento y clasifica MIME. El login viaja con el connectionId para
            // que una sesión no acepte uploads de otra.
            route("/api/attachments/{connectionId}") {
                install(syncGate)
                post {
                    val token = call.request.cookies[SESSION_COOKIE] ?: return@post
                    if (!call.request.contentType().match(ContentType.Application.OctetStream)) {
                        return@post call.respond(HttpStatusCode.UnsupportedMediaType)
                    }
                    val params = call.request.queryParameters
                    val seq = params["seq"]?.toLongOrNull()?.takeIf { it >= 0 }
                    val name = params["name"]?.takeIf { it.isNotBlank() && it.length <= 256 }
                    val mime = params["mime"]?.takeIf { it.length <= 128 }
                    val forced = params["type"]
                    val rawSize = call.request.headers[HttpHeaders.ContentLength]
                    val size = rawSize?.toLongOrNull()
                    if (seq == null || name == null || (rawSize != null && (size == null || size < 0)) ||
                        (forced != null && forced !in setOf("image", "video"))) {
                        return@post call.respond(HttpStatusCode.BadRequest)
                    }
                    if (size != null && size > com.newoether.agora.util.AttachmentFiles.MAX_ATTACHMENT_BYTES) {
                        return@post call.respond(HttpStatusCode.PayloadTooLarge)
                    }
                    coroutineScope {
                        // Un logout durante el upload corta el request con el token revocado.
                        val requestJob = currentCoroutineContext()[kotlinx.coroutines.Job]!!
                        val revoke = launch { auth.awaitSessionEnd(token); requestJob.cancel() }
                        try {
                            val status = upload(
                                token, call.parameters["connectionId"].orEmpty(), seq, name, mime,
                                forced, size, call.receiveChannel(),
                            )
                            call.response.header(HttpHeaders.CacheControl, "no-store")
                            call.respond(if (auth.isValidSession(token)) status else HttpStatusCode.Forbidden)
                        } finally {
                            revoke.cancel()
                        }
                    }
                }
            }
            get("/") { call.respondAsset(INDEX) }
            get("/assets/{path...}") {
                val path = call.parameters.getAll("path").orEmpty().joinToString("/")
                call.respondAsset(path)
            }
            // Público como los otros archivos estáticos: la página de sign-in también lleva tema.
            get("/theme.css") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondText(themeCss(), ContentType.Text.CSS.withParameter("charset", "utf-8"))
            }
            get(WebUiTheme.FONT_PATH) {
                val bytes = readAppFont()
                if (bytes == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respondBytes(bytes, fontTypeOf(bytes))
                }
            }
            // La fuente de código detrás de los estilos Markdown de style.css.
            get("$MONO_FONT_PATH/{style}") {
                val bytes = call.parameters["style"]?.let(readMonoFont)
                if (bytes == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respondBytes(bytes, ContentType("font", "ttf"))
                }
            }
            post("/api/login") { call.login() }
            post("/api/logout") {
                if (!call.acceptsPost()) return@post
                auth.logout(call.request.cookies[SESSION_COOKIE])
                call.response.cookies.append(call.sessionCookie(value = "", maxAge = 0))
                call.respond(HttpStatusCode.NoContent)
            }
            get("/api/session") {
                val signedIn = auth.isValidSession(call.request.cookies[SESSION_COOKIE])
                call.respondJson(HttpStatusCode.OK, SessionResponse(signedIn))
            }
        }
    }

    private suspend fun ApplicationCall.login() {
        if (!acceptsPost()) return
        val length = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (length == null || length > MAX_LOGIN_BODY_BYTES) {
            respondJson(HttpStatusCode.PayloadTooLarge, ErrorResponse("invalid_request"))
            return
        }
        val password = runCatching { json.decodeFromString<LoginRequest>(receiveText()).password }
            .getOrNull()
        if (password == null) {
            respondJson(HttpStatusCode.BadRequest, ErrorResponse("invalid_request"))
            return
        }
        when (val result = auth.login(password)) {
            is WebUiLoginResult.Success -> {
                response.cookies.append(sessionCookie(result.sessionToken, maxAge = null))
                respondJson(HttpStatusCode.OK, SessionResponse(signedIn = true))
            }
            is WebUiLoginResult.WrongPassword -> respondJson(
                HttpStatusCode.Unauthorized,
                ErrorResponse("wrong_password", attemptsLeft = result.attemptsLeft),
            )
            is WebUiLoginResult.LockedOut -> {
                val seconds = ((result.untilMillis - clock()).coerceAtLeast(0) + 999) / 1000
                response.header(HttpHeaders.RetryAfter, seconds.toString())
                respondJson(
                    HttpStatusCode.TooManyRequests,
                    ErrorResponse("locked", retryAfterSeconds = seconds),
                )
            }
            WebUiLoginResult.NotConfigured ->
                respondJson(HttpStatusCode.ServiceUnavailable, ErrorResponse("not_configured"))
        }
    }

    /** Corre la sesión sync y cierra el socket apenas termina su sesión de login. */
    private suspend fun DefaultWebSocketServerSession.serveSync() {
        val token = call.request.cookies[SESSION_COOKIE] ?: return
        val texts = incoming.consumeAsFlow()
            .filterIsInstance<Frame.Text>()
            .map { it.readText() }
            .produceIn(this)
        val signOut = launch {
            auth.awaitSessionEnd(token)
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "signed_out"))
        }
        try {
            syncSession(token, texts) { text -> outgoing.send(Frame.Text(text)) }
        } finally {
            signOut.cancel()
        }
    }

    /** Responde 403/415 y retorna false salvo que este POST sea JSON same-origin. */
    private suspend fun ApplicationCall.acceptsPost(): Boolean {
        if (!isSameOrigin()) {
            respondJson(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            return false
        }
        if (!request.contentType().match(ContentType.Application.Json)) {
            respondJson(HttpStatusCode.UnsupportedMediaType, ErrorResponse("invalid_request"))
            return false
        }
        return true
    }

    private fun ApplicationCall.isSameOrigin(): Boolean {
        val origin = request.headers[HttpHeaders.Origin] ?: return true
        val host = request.headers[HttpHeaders.Host] ?: return false
        val parsed = runCatching { URLBuilder(origin).build() }.getOrNull() ?: return false
        // Compara host y puerto efectivo; cualquiera de los dos puede omitir un puerto default.
        // Los hosts IPv6 van entre corchetes ("[::1]:8686"), así el puerto va tras el corchete final.
        val portSeparator = host.lastIndexOf(':').takeIf { it > host.lastIndexOf(']') } ?: -1
        val hostName = (if (portSeparator >= 0) host.substring(0, portSeparator) else host)
            .removePrefix("[").removeSuffix("]")
        val hostPort = if (portSeparator >= 0) {
            host.substring(portSeparator + 1).toIntOrNull() ?: return false
        } else {
            parsed.protocol.defaultPort
        }
        val originHost = parsed.host.removePrefix("[").removeSuffix("]")
        return originHost.equals(hostName, ignoreCase = true) && parsed.port == hostPort
    }

    private suspend fun ApplicationCall.respondAsset(path: String) {
        val bytes = path.takeIf(::isSafeAssetPath)?.let(readAsset)
        if (bytes == null) {
            respond(HttpStatusCode.NotFound)
            return
        }
        respondBytes(bytes, contentTypeOf(path))
    }

    private suspend inline fun <reified T> ApplicationCall.respondJson(
        status: HttpStatusCode,
        body: T,
    ) {
        response.header(HttpHeaders.CacheControl, "no-store")
        respond(TextContent(json.encodeToString(body), ContentType.Application.Json, status))
    }

    private fun ApplicationCall.sessionCookie(value: String, maxAge: Int?) = Cookie(
        name = SESSION_COOKIE,
        value = value,
        maxAge = maxAge,
        path = "/",
        httpOnly = true,
        secure = secureCookies(this),
        extensions = mapOf("SameSite" to "Strict"),
    )

    @Serializable private data class LoginRequest(val password: String)

    @Serializable private data class SessionResponse(val signedIn: Boolean)

    @Serializable
    private data class ErrorResponse(
        val error: String,
        val attemptsLeft: Int? = null,
        val retryAfterSeconds: Long? = null,
    )

    companion object {
        const val SESSION_COOKIE = "agora_session"
        const val INDEX = "index.html"
        const val MONO_FONT_PATH = "/fonts/mono"
        private const val MAX_LOGIN_BODY_BYTES = 4_096L
        /** Los comandos del navegador son pequeños; esto acota lo que un frame entrante puede alocar. */
        private const val MAX_SYNC_FRAME_BYTES = 64L * 1024L
        private val SYNC_PING_PERIOD = 20.seconds
        private val SYNC_TIMEOUT = 45.seconds
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        private val SAFE_ASSET_PATH = Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*(/[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*)*")

        /** Solo nombres relativos planos: no `..`, sin slash inicial, sin separadores codificados. */
        internal fun isSafeAssetPath(path: String): Boolean = SAFE_ASSET_PATH.matches(path)

        private fun contentTypeOf(path: String): ContentType = when (path.substringAfterLast('.')) {
            "html" -> ContentType.Text.Html.withParameter("charset", "utf-8")
            "js", "mjs" -> ContentType.Text.JavaScript.withParameter("charset", "utf-8")
            "css" -> ContentType.Text.CSS.withParameter("charset", "utf-8")
            "svg" -> ContentType.Image.SVG
            "png" -> ContentType.Image.PNG
            "json" -> ContentType.Application.Json
            "woff2" -> ContentType("font", "woff2")
            else -> ContentType.Application.OctetStream
        }

        /** Distingue la fuente servida por firma de archivo: OTTO = OpenType, resto = TrueType. */
        private fun fontTypeOf(bytes: ByteArray): ContentType =
            if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "OTTO") {
                ContentType("font", "otf")
            } else {
                ContentType("font", "ttf")
            }
    }
}

/** Hardening del navegador para cada respuesta: sin framing, sin sniffing, sin cargas third-party. */
private val SecurityHeaders = createApplicationPlugin("WebUiSecurityHeaders") {
    onCall { call ->
        with(call.response) {
            header("X-Content-Type-Options", "nosniff")
            header("X-Frame-Options", "DENY")
            header("Referrer-Policy", "no-referrer")
            header(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
                    "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
            )
        }
    }
}
