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
import io.ktor.server.application.install
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun install(application: Application) = with(application) {
        install(SecurityHeaders)
        routing {
            get("/") { call.respondAsset(INDEX) }
            get("/assets/{path...}") {
                val path = call.parameters.getAll("path").orEmpty().joinToString("/")
                call.respondAsset(path)
            }
            post("/api/login") { call.login() }
            post("/api/logout") {
                if (!call.acceptsPost()) return@post
                auth.logout(call.request.cookies[SESSION_COOKIE])
                call.response.cookies.append(sessionCookie(value = "", maxAge = 0))
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

    private fun sessionCookie(value: String, maxAge: Int?) = Cookie(
        name = SESSION_COOKIE,
        value = value,
        maxAge = maxAge,
        path = "/",
        httpOnly = true,
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
        private const val MAX_LOGIN_BODY_BYTES = 4_096L
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
            else -> ContentType.Application.OctetStream
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
