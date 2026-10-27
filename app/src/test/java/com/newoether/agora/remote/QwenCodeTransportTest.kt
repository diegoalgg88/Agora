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
import kotlinx.coroutines.flow.first
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
    private enum class StreamMode { IDLE, ONE_FRAME, LIST_REPLY, NEW_SESSION, LOAD_REPLAY, PERMISSION }

    /** Latch por request: habilita la escritura del reply una vez registrado el waiter. */
    private val requested = mutableMapOf<String, CompletableDeferred<Unit>>()

    @Volatile private var mode = StreamMode.IDLE
    private var server: EmbeddedServer<*, *>? = null
    private var port = 0
    @Before fun start() {
        requested.clear()
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
                        when {
                            body.contains("session/list") -> requested.getOrPut("list") { CompletableDeferred() }.complete(Unit)
                            body.contains("session/new") -> requested.getOrPut("new") { CompletableDeferred() }.complete(Unit)
                            body.contains("session/prompt") -> requested.getOrPut("prompt") { CompletableDeferred() }.complete(Unit)
                            body.contains("session/set_model") -> requested.getOrPut("set_model") { CompletableDeferred() }.complete(Unit)
                            body.contains("session/load") -> requested.getOrPut("load") { CompletableDeferred() }.complete(Unit)
                            body.contains("session/permission") -> requested.getOrPut("permission") { CompletableDeferred() }.complete(Unit)
                        }
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
                            // Reply del request session/list (id=2: initialize usó el 1):
                            // sessions viaja como ARRAY — el shape real del dispatcher. El
                            // latch espera a que el POST registre el waiter antes de escribir.
                            StreamMode.LIST_REPLY -> {
                                requested.getOrPut("list") { CompletableDeferred() }.await()
                                write("""data: {"jsonrpc":"2.0","id":2,"result":{"sessions":[{"sessionId":"s1","title":"Session One","updatedAt":1698000000000,"cwd":"C:\\w"}],"nextCursor":null}}""")
                                write("\n\n")
                                flush()
                            }
                            // session/new (id=2) trae el catálogo ACP-standard en `models`;
                            // luego session/prompt (id=3) cierra el turno. El latch por-request
                            // evita escribir el frame antes de registrar su waiter.
                            StreamMode.NEW_SESSION -> {
                                requested.getOrPut("new") { CompletableDeferred() }.await()
                                write("""data: {"jsonrpc":"2.0","id":2,"result":{"sessionId":"s-new","models":{"currentModelId":"m1","availableModels":[{"id":"m1"},{"id":"m2"}]}}}""")
                                write("\n\n")
                                flush()
                                requested.getOrPut("prompt") { CompletableDeferred() }.await()
                                write("""data: {"jsonrpc":"2.0","id":3,"result":{"turnId":"t1"}}""")
                                write("\n\n")
                                flush()
                            }
                            // session/load (id=2) rearma el replay: session/update frames fluyen
                            // por el stream (user_message + agent_message) — el historial NO
                            // viene en el reply, sino por el stream (initialReplayPending).
                            StreamMode.LOAD_REPLAY -> {
                                requested.getOrPut("load") { CompletableDeferred() }.await()
                                write("""data: {"jsonrpc":"2.0","id":2,"result":{"sessionId":"s-load","replayStatus":{}}}""")
                                write("\n\n")
                                flush()
                                write("""data: {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s-load","update":{"sessionUpdate":"user_message","content":[{"type":"text","text":"Historial"}],"messageId":"u1","turnId":"t9"}}}""")
                                write("\n\n")
                                flush()
                                write("""data: {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s-load","update":{"sessionUpdate":"agent_message","content":[{"type":"text","text":"Respuesta"}],"messageId":"a1","turnId":"t9"}}}""")
                                write("\n\n")
                                flush()
                            }
                            // Notificación permission_request por el stream conn-scoped
                            // (id=1 ya lo usó initialize); tras el POST de session/permission
                            // su reply (id=2) cierra el ciclo de aprobación.
                            StreamMode.PERMISSION -> {
                                write("""data: {"jsonrpc":"2.0","method":"permission_request","params":{"requestId":"req-1","sessionId":"s-load","toolCall":{"title":"Ejecutar comando","kind":"execute","arguments":[{"type":"text","text":"rm -rf /tmp/x"}]},"options":[{"optionId":"allow_once","name":"Permitir"},{"optionId":"reject","name":"Rechazar"}]}}""")
                                write("\n\n")
                                flush()
                                requested.getOrPut("permission") { CompletableDeferred() }.await()
                                write("""data: {"jsonrpc":"2.0","id":2,"result":{}}""")
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

    @Test fun sessionListReplyWithArrayShapeParsesWithoutThrowing() = runBlocking {
        mode = StreamMode.LIST_REPLY
        val client = QwenCodeClient("http://127.0.0.1:$port", "token")
        try {
            client.connect()
            // El dispatcher manda `sessions` como ARRAY; el accessor .jsonObject lanzaba
            // IllegalArgumentException y tumbaba el read loop. sessions() debe parsear.
            val page = withTimeout(10_000) { client.sessions() }
            assertEquals(1, page.sessions.size)
            assertEquals("s1", page.sessions.single().id)
            assertEquals("Session One", page.sessions.single().title)
        } finally {
            client.close()
        }
    }

    @Test fun createCachesTheModelsCatalogFromTheSessionNewReply() = runBlocking {
        mode = StreamMode.NEW_SESSION
        val client = QwenCodeClient("http://127.0.0.1:$port", "token")
        try {
            client.connect()
            // Antes del primer session/new el catálogo está vacío (degradación v1).
            assertEquals(emptyList<RemoteModel>(), client.models())
            val session = client.create("Hola", "client-1")
            assertEquals("s-new", session.id)
            // El reply de session/new trae models {currentModelId, availableModels:[{id}]}:
            // el catálogo queda cacheado y models() lo expone.
            val models = client.models()
            assertEquals(listOf("m1", "m2"), models.map { it.id })
        } finally { client.close() }
    }

    @Test fun conversationTriggersSessionLoadAndReplayFeedsEventsWithTopologyNodes() = runBlocking {
        mode = StreamMode.LOAD_REPLAY
        val client = QwenCodeClient("http://127.0.0.1:$port", "token")
        try {
            client.connect()
            // El ViewModel colecta events() ANTES de abrir: el replay del ring viaja por ahí.
            val pages = kotlinx.coroutines.channels.Channel<RemoteConversationPage>(8)
            val events = kotlinx.coroutines.CoroutineScope(Dispatchers.Default + SupervisorJob())
                .launch { client.events("s-load").collect { pages.send(it) } }
            // conversation() dispara session/load — el reply NO trae el transcript; el
            // server rearma el replay hacia el stream (initialReplayPending). El contrato
            // es una página POR session/update: acumular hasta que el replay complete.
            client.conversation("s-load")
            var page = withTimeout(10_000) { pages.receive() }
            withTimeout(10_000) {
                while (page.messages.none { it.role == "assistant" }) { page = pages.receive() }
            }
            events.cancel()
            // Contrato de cachePage: nodes con los mismos ids, mismo orden que messages.
            assertEquals(page.messages.map { it.id }, page.nodes.map { it.id })
            // El replay entregó el historial: user + agent del ring del server.
            val texts = page.messages.map { it.role to it.text }
            assertTrue("user_message del replay ausente: $texts", texts.any { it.first == "user" && it.second == "Historial" })
            assertTrue("agent_message del replay ausente: $texts", texts.any { it.first == "assistant" && it.second == "Respuesta" })
        } finally { client.close() }
    }

    @Test fun permissionRequestSurfacesViaStateFlowAndRespondsBySessionPermission() = runBlocking {
        mode = StreamMode.PERMISSION
        val client = QwenCodeClient("http://127.0.0.1:$port", "token")
        try {
            client.connect()
            // El collector conn-scoped de connect() recibe la notificación y la expone.
            val request = withTimeout(10_000) {
                client.pendingPermissionRequests.first { it.isNotEmpty() }.single()
            }
            assertEquals("req-1", request.requestId)
            assertEquals("s-load", request.sessionId)
            assertEquals("Ejecutar comando", request.title)
            assertEquals(listOf("allow_once", "reject"), request.options.map { it.optionId })
            assertEquals("rm -rf /tmp/x", request.rawArguments)
            // La respuesta viaja como session/permission con el outcome seleccionado.
            client.respondToPermission("req-1", "allow_once")
            withTimeout(10_000) {
                while (client.pendingPermissionRequests.value.any { it.requestId == "req-1" }) delay(50)
            }
            assertTrue(client.pendingPermissionRequests.value.isEmpty())
        } finally { client.close() }
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
