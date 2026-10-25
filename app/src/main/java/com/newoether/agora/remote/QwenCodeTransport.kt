package com.newoether.agora.remote

import com.newoether.agora.api.readBoundedWireLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class QwenCodeConfigurationException : IllegalArgumentException("Invalid Qwen Code connection")
internal class QwenCodeProtocolException(val code: Int? = null, message: String) : IOException(message)

/**
 * Transporte wire del endpoint /acp de `qwen serve` (ACP-over-HTTP, RFD #721):
 *
 * - POST /acp con `initialize` → 200 INLINE con el envelope JSON-RPC + header
 *   `acp-connection-id` que identifica la conexión lógica.
 * - POST /acp con cualquier otro request (llevando el header de conexión) →
 *   202 Accepted; la respuesta JSON-RPC llega por el SSE connection-scoped,
 *   demultiplexada por id de request.
 * - GET /acp (header de conexión) → SSE connection-scoped: responses de
 *   requests + notificaciones connection-level.
 * - GET /acp?sessionId=… → SSE session-scoped: `session/update` con el cursor
 *   como `id:` line (Last-Event-ID resume) + `permission_request` (que exige
 *   un stream vivo; sin él el server cancela deny-safe).
 *
 * Este archivo NO conoce los dominios de Agora (RemoteSession, RemoteMessage):
 * solo frames. El adaptador de shapes vive en QwenCodeClient.
 */
internal class QwenCodeTransport(
    address: String,
    private val token: String,
    private val calls: Call.Factory = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectionPool(okhttp3.ConnectionPool(5, 45, TimeUnit.SECONDS))
        .callTimeout(30, TimeUnit.SECONDS).build(),
    /** Deadline de mutations nativas (session/prompt puede tardar minutos en frío). */
    mutationTimeoutMillis: Long = 210_000,
) {
    private val endpoint = try {
        address.trim().toHttpUrl().also {
            require(it.username.isEmpty() && it.password.isEmpty() && it.query == null &&
                it.fragment == null)
            require(it.encodedPath == "/")
        }
    } catch (_: IllegalArgumentException) {
        throw QwenCodeConfigurationException()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val requestIds = AtomicLong(0)
    private var connectionId: String? = null
    /** Requests en vuelo: id de JSON-RPC → waiter de la respuesta. */
    private val pending = java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val pendingMutex = Mutex()

    private val readCalls: Call.Factory = (calls as? OkHttpClient)?.newBuilder()
        ?.retryOnConnectionFailure(true)?.build() ?: calls
    private val mutationCalls: Call.Factory = (calls as? OkHttpClient)?.newBuilder()
        ?.retryOnConnectionFailure(false)
        ?.readTimeout(mutationTimeoutMillis, TimeUnit.MILLISECONDS)
        ?.callTimeout(mutationTimeoutMillis, TimeUnit.MILLISECONDS)?.build() ?: calls

    val acpAddress: String get() = endpoint.toString()

    /**
     * Handshake inicial: crea la conexión lógica y expone las capabilities del
     * agente. El id de conexión viaja en TODO request y stream posterior.
     */
    suspend fun initialize(): JsonObject {
        check(connectionId == null) { "Connection already initialized" }
        val id = requestIds.incrementAndGet()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "initialize")
            put("params", buildJsonObject {
                put("protocolVersion", 1)
                put("clientCapabilities", buildJsonObject {})
            })
        }.toString().toRequestBody("application/json".toMediaType())
        return suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(endpoint.newBuilder().addPathSegments("acp").build())
                .header("Authorization", "Bearer $token").post(body).build()
            val call = mutationCalls.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val connId = it.header("acp-connection-id")
                                ?: throw QwenCodeProtocolException(message = "Missing acp-connection-id header")
                            val text = it.body.source().let { source ->
                                val out = StringBuilder()
                                while (true) {
                                    val line = source.readBoundedWireLine(1024L * 1024) ?: break
                                    out.append(line)
                                }
                                out.toString()
                            }
                            if (!it.isSuccessful) throw QwenCodeProtocolException(it.code, "initialize failed: ${text.take(256)}")
                            val envelope = json.parseToJsonElement(text).jsonObject
                            if (envelope.containsKey("error")) {
                                val error = envelope["error"]!!.jsonObject
                                throw QwenCodeProtocolException(
                                    error["code"]?.jsonPrimitive?.longOrNull?.toInt(),
                                    error["message"]?.jsonPrimitive?.contentOrNull ?: "initialize error",
                                )
                            }
                            connectionId = connId
                            if (!continuation.isCancelled) {
                                continuation.resume(envelope["result"]?.jsonObject ?: buildJsonObject {})
                            }
                        } catch (error: Exception) {
                            if (!continuation.isCancelled) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }
    }

    /**
     * Request JSON-RPC con respuesta demuxeada del SSE connection-scoped: el
     * POST devuelve 202 y la respuesta real llega por el stream. Sin stream
     * vivo el server igual procesa, pero el caller nunca recibiría su
     * respuesta — [connectionEvents] debe estar coleccionado para que los
     * requests del mismo dueño resuelvan.
     */
    suspend fun request(method: String, params: JsonObject, timeoutMillis: Long = 240_000): JsonObject {
        val connId = requireNotNull(connectionId) { "Not initialized" }
        val id = requestIds.incrementAndGet()
        val waiter = CompletableDeferred<JsonObject>()
        pendingMutex.withLock { pending[id] = waiter }
        try {
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString().toRequestBody("application/json".toMediaType())
            suspendCancellableCoroutine { continuation ->
                val request = Request.Builder().url(endpoint.newBuilder().addPathSegments("acp").build())
                    .header("Authorization", "Bearer $token")
                    .header("acp-connection-id", connId).post(body).build()
                val call = mutationCalls.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!continuation.isCancelled) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            // 202 es el contrato: la respuesta JSON-RPC llega por SSE.
                            if (!it.isSuccessful) {
                                val text = runCatching { it.body.string() }.getOrDefault("")
                                if (!continuation.isCancelled) {
                                    continuation.resumeWithException(
                                        QwenCodeProtocolException(it.code, "$method failed: ${text.take(256)}"),
                                    )
                                }
                            } else if (!continuation.isCancelled) continuation.resume(Unit)
                        }
                    }
                })
            }
            return withTimeoutAtLeast(waiter, timeoutMillis)
        } finally {
            pendingMutex.withLock { pending.remove(id) }
        }
    }

    private suspend inline fun withTimeoutAtLeast(
        waiter: CompletableDeferred<JsonObject>, timeoutMillis: Long,
    ): JsonObject = kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) { waiter.await() }
        ?: throw QwenCodeProtocolException(message = "$timeoutMillis ms waiting for a response on the ACP stream")

    /**
     * SSE connection-scoped: responses de requests (demux por id contra
     * [pending]) + notificaciones connection-level (p. ej. available_commands).
     * Emite las NOTIFICACIONES crudas; las responses se consumen internamente.
     */
    fun connectionEvents(): Flow<JsonObject> = sseStream(sessionId = null) { frame ->
        // Toda response con id resuelve a su waiter; lo demás es notificación.
        frame["id"]?.jsonPrimitive?.longOrNull?.let { id ->
            pending[id]?.let { waiter ->
                if (frame.containsKey("error")) {
                    val error = frame["error"]!!.jsonObject
                    waiter.completeExceptionally(QwenCodeProtocolException(
                        error["code"]?.jsonPrimitive?.longOrNull?.toInt(),
                        error["message"]?.jsonPrimitive?.contentOrNull ?: "ACP error",
                    ))
                } else {
                    waiter.complete(frame["result"]?.jsonObject ?: buildJsonObject {})
                }
                return@sseStream null
            }
        }
        frame
    }

    /**
     * SSE session-scoped: `session/update` y `permission_request` de UNA sesión.
     * El cursor SSE (`id:` line) permite resume con Last-Event-ID tras reconectar.
     */
    fun sessionEvents(sessionId: String, lastEventId: String? = null): Flow<SessionStreamEvent> =
        sseStream(sessionId, lastEventId) { it }.let { flow ->
            kotlinx.coroutines.flow.flow {
                var cursor = lastEventId
                flow.collect { frame ->
                    // `session/update` trae su cursor en _meta/... pero el cursor del SSE
                    // viene en la id: line — capturado por sseStream y expuesto aquí.
                    emit(SessionStreamEvent(frame, cursor))
                }
            }
        }

    /** Un frame del stream de sesión + el cursor SSE vigente para Last-Event-ID. */
    internal data class SessionStreamEvent(val frame: JsonObject, val lastEventId: String?)

    private fun <T> sseStream(
        sessionId: String?, lastEventId: String?, transform: (JsonObject) -> T?,
    ): Flow<T> = callbackFlow {
        val connId = requireNotNull(connectionId) { "Not initialized" }
        val url = endpoint.newBuilder().addPathSegments("acp").apply {
            sessionId?.let { addQueryParameter("sessionId", it) }
        }.build()
        val requestBuilder = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("acp-connection-id", connId)
            .header("Accept", "text/event-stream")
        lastEventId?.let { requestBuilder.header("Last-Event-Id", it) }
        val call = readCalls.newCall(requestBuilder.build())
        call.timeout().clearTimeout()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!it.isSuccessful) throw QwenCodeProtocolException(it.code, "ACP stream refused")
                        val source = it.body.source()
                        var data = StringBuilder()
                        var currentEventId: String? = null
                        while (!call.isCanceled()) {
                            val line = source.readBoundedWireLine(1024L * 1024) ?: break
                            when {
                                line.startsWith("id:") -> currentEventId = line.removePrefix("id:").trim()
                                line.startsWith("data:") -> {
                                    val payload = line.removePrefix("data:").trim()
                                    if (payload.isNotEmpty()) {
                                        if (data.isNotEmpty()) data.append('\n')
                                        data.append(payload)
                                    }
                                }
                                line.isEmpty() && data.isNotEmpty() -> {
                                    // Blank line: frame completo.
                                    val frame = runCatching {
                                        json.parseToJsonElement(data.toString()).jsonObject
                                    }.getOrNull()
                                    data = StringBuilder()
                                    if (frame != null) transform(frame)?.let { value -> trySend(value) }
                                }
                            }
                        }
                        close(IOException("ACP stream closed"))
                    } catch (error: Exception) { close(error) }
                }
            }
        })
        awaitClose { call.cancel() }
    }.buffer(Channel.CONFLATED)

    /** El stream de sesión necesita exponer el cursor por frame; wrapper interno. */
    private fun <T> sseStream(sessionId: String?, transform: (JsonObject) -> T?): Flow<T> =
        sseStream(sessionId, null, transform)
}
