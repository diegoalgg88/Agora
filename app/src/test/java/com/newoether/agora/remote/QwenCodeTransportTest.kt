package com.newoether.agora.remote

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * El fallo de transporte del SSE (timeout de read, EOF del server, cancelación) debe
 * degradarse a error tipado/completación del flow — jamás una excepción escapando al
 * handler no-atrapado del proceso (crash del S22 Ultra). El server embebido reproduce
 * /acp de qwen serve: initialize inline con header, requests 202 y stream SSE.
 */
class QwenCodeTransportTest {
    /** Comportamiento del stream SSE GET /acp. */
    private enum class StreamMode { IDLE, ONE_FRAME }

    @Volatile private var mode = StreamMode.IDLE
    private var server: EmbeddedServer<*, *>? = null
    private var port = 0

    @Before fun start() {
        val embedded = embeddedServer(CIO, port = 0) {
            routing {
                post("/acp") {
                    val body = call.receiveText()
                    if (body.contains("initialize")) {
                        call.response.header("acp-connection-id", "conn-test")
                        call.respondText(
                            """{"jsonrpc":"2.0","id":1,"result":{"agentInfo":{"name":"Qwen Code"}}}""",
                            ContentType.Application.Json,
                        )
                    } else {
                        // Contrato /acp: 202, la respuesta real llega por el stream SSE.
                        call.respondText("", status = HttpStatusCode.Accepted)
                    }
                }
                get("/acp") {
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        when (mode) {
                            // Stream abierto que nunca escribe: read bloqueado hasta el timeout.
                            StreamMode.IDLE -> CompletableDeferred<Unit>().await()
                            // Un frame y EOF: readBoundedWireLine debe ver null y COMPLETAR.
                            StreamMode.ONE_FRAME -> {
                                write("""data: {"jsonrpc":"2.0","method":"available_commands","params":{}}""")
                                write("\n\n")
                                flush()
                            }
                        }
                    }
                }
            }
        }.start(wait = false)
        server = embedded
        // resolvedConnectors() es suspend: bloquear aquí es seguro (setup del @Before).
        port = runBlocking { embedded.engine.resolvedConnectors().first().port }
    }

    @After fun stop() { server?.stop(100, 500) }

    private fun transport(streamReadTimeoutMillis: Long = 90_000) =
        QwenCodeTransport("http://127.0.0.1:$port", "token", streamReadTimeoutMillis = streamReadTimeoutMillis)

    @Test fun idleStreamTimeoutSurfacesAsTypedFailureAndFailsPendingRequests() = runBlocking {
        mode = StreamMode.IDLE
        val transport = transport(streamReadTimeoutMillis = 300)
        transport.initialize()
        // Request en vuelo (202) cuya respuesta solo podría llegar por el stream que va a morir.
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pending = scope.async { transport.request("session/list", buildJsonObject {}, timeoutMillis = 60_000) }
        val startedAt = System.currentTimeMillis()
        // El timeout del stream debe llegar al collector como fallo tipado, rápido — no crashear.
        val streamFailure = runCatching {
            withTimeout(5_000) { transport.connectionEvents().collect { } }
        }.exceptionOrNull()
        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue("El fallo debe ser IOException tipado, fue: $streamFailure",
            streamFailure is IOException)
        assertTrue("El timeout debe llegar en ~300ms, tardó ${elapsed}ms", elapsed < 5_000)
        // failPending: el request en vuelo falló con la causa del stream, no colgó 60s.
        val requestFailure = runCatching { withTimeout(5_000) { pending.await() } }.exceptionOrNull()
        assertTrue("El request en vuelo debió fallar rápido", requestFailure is IOException)
        scope.cancel()
    }

    @Test fun serverStreamEofCompletesTheFlowInsteadOfThrowing() = runBlocking {
        mode = StreamMode.ONE_FRAME
        val transport = transport(streamReadTimeoutMillis = 60_000)
        transport.initialize()
        val frames = mutableListOf<JsonObject>()
        // EOF del server = completación normal del flow; el consumidor decide reconectar.
        withTimeout(5_000) { transport.connectionEvents().collect { frames.add(it) } }
        assertEquals(1, frames.size)
        assertEquals("available_commands", frames.single()["method"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test fun cancelledCollectorPropagatesCancellationInsteadOfWaitingForTimeout() = runBlocking {
        mode = StreamMode.IDLE
        val transport = transport(streamReadTimeoutMillis = 60_000)
        transport.initialize()
        val collector = launch(Dispatchers.Default + SupervisorJob()) {
            transport.connectionEvents().collect { }
        }
        delay(200)
        // Carrera cancelación-vs-timeout: el stream está idle (60s de timeout); la
        // cancelación debe ganar de inmediato via awaitClose { call.cancel() }.
        val startedAt = System.currentTimeMillis()
        withTimeout(5_000) { collector.cancelAndJoin() }
        assertTrue(System.currentTimeMillis() - startedAt < 5_000)
        assertTrue(collector.isCancelled)
    }

    @Test fun reInitializeFailsPendingWaitersAndReplacesTheConnection() = runBlocking {
        mode = StreamMode.IDLE
        val transport = transport(streamReadTimeoutMillis = 60_000)
        transport.initialize()
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pending = scope.async { transport.request("session/list", buildJsonObject {}, timeoutMillis = 60_000) }
        delay(200)
        // El health-probe del ViewModel re-inicializa: los waiters del connId viejo
        // fallan de inmediato con la causa de reset, y el initialize nuevo acepta.
        transport.initialize()
        val failure = runCatching { withTimeout(5_000) { pending.await() } }.exceptionOrNull()
        assertTrue("El waiter viejo debió fallar con reset, fue: $failure",
            failure is IOException)
        scope.cancel()
    }
}
