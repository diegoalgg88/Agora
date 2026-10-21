package com.newoether.agora.webui

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.forDisplay
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.components.parseLatexSpans
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.ConversationMessagePayloadHydration
import com.newoether.agora.viewmodel.ConversationStateRegistry
import com.newoether.agora.viewmodel.ConversationUiState
import com.newoether.agora.viewmodel.toUiChatMessageStub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Espejo read-only del estado del chat para una conexión del WebUI.
 *
 * Cada conexión elige su propia conversación; nada aquí cambia lo que el teléfono muestra. Los
 * datos siguen las reglas de carga de la propia app: la lista usa la proyección estrecha del
 * drawer, abrir una conversación corre la misma recuperación de runtime que corre la app, la
 * conversación abierta envía solo la topología de su rama seleccionada, y el cuerpo de un
 * mensaje se lee solo mientras el navegador observa esa fila. Room sigue siendo la única fuente;
 * esta clase no guarda copia del grafo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class WebUiSync(
    private val conversations: ConversationRepository,
    private val registry: ConversationStateRegistry,
    private val executionCoordinator: ConversationExecutionCoordinator,
    private val hydration: ConversationMessagePayloadHydration,
    private val customProviders: StateFlow<List<CustomProviderConfig>>,
    private val parseInlineDollarMath: StateFlow<Boolean>,
    /**
     * Contexto de visualización del navegador (shim del presentation block c6b2c8ef). Vacío en
     * tests; producción lo pasa desde AppContainer — sin este evento el Composer del frontend
     * crashea leyendo display.reduceMotion (shell.js:440, sin optional chaining).
     */
    private val display: Flow<WebSyncEvent.Display> = emptyFlow(),
    /** Construye el [WebUiChatSession] de una conexión con el scope de esa conexión. */
    private val openChatSession: (CoroutineScope) -> WebUiChatSession,
    private val projectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Sesiones vivas por connectionId; el token de login amarra cada una a su dueño. */
    private val connections = java.util.concurrent.ConcurrentHashMap<String, Pair<String, WebUiChatSession>>()

    /** Un POST de upload llega con su connectionId; solo su sesión lo acepta. */
    suspend fun upload(
        login: String, connectionId: String, seq: Long, name: String, mime: String?, forcedType: String?,
        size: Long?, input: io.ktor.utils.io.ByteReadChannel,
    ): io.ktor.http.HttpStatusCode {
        val session = connections[connectionId]?.takeIf { it.first == login }?.second
            ?: return io.ktor.http.HttpStatusCode.NotFound
        return session.upload(seq, name, mime, forcedType, size, input)
    }

    /** Un GET de preview llega con su connectionId; solo su sesión lo acepta. */
    suspend fun previewAttachment(
        login: String, connectionId: String, seq: Long, id: String, kind: String, index: Int,
        consume: suspend (java.io.File, String) -> Unit,
    ): Boolean {
        val session = connections[connectionId]?.takeIf { it.first == login }?.second ?: return false
        return session.previewAttachment(seq, id, kind, index, consume)
    }

    /**
     * Sirve una conexión hasta que [incoming] cierra. Los comandos llegan como texto JSON; cada
     * evento se entrega a [send] desde una sola coroutine, así [send] no necesita lock propio.
     */
    suspend fun serve(login: String, incoming: ReceiveChannel<String>, send: suspend (String) -> Unit) =
        coroutineScope {
            // Rendezvous: un productor espera a que el sender tome su evento, así un navegador
            // lento frena a los productores en vez de inflar una cola.
            val outbound = Channel<WebSyncEvent>()
            launch { for (event in outbound) send(json.encodeToString(WebSyncEvent.serializer(), event)) }
            // El display va primero y con replay=1: el Composer del navegador lee
            // display.reduceMotion sin optional chaining y crashea si aún no llegó.
            val displayContext = display.distinctUntilChanged()
                .shareIn(this, SharingStarted.Eagerly, replay = 1)
            launch { displayContext.collect { outbound.send(it) } }
            val session = openChatSession(this)
            val list = combine(
                conversations.getAllConversations(),
                registry.activeConversationIds,
            ) { items, active -> items to active }
                .shareIn(this, SharingStarted.Eagerly, replay = 1)
            launch {
                list.map { (items, active) ->
                    WebSyncEvent.Conversations(
                        items.map { it.toWeb(generating = it.id in active) },
                    )
                }
                    .distinctUntilChanged()
                    .collect { outbound.send(it) }
            }
            val watched = MutableStateFlow<Set<String>>(emptySet())
            val connectionId = java.util.UUID.randomUUID().toString()
            val connectionJob = coroutineContext[Job]!!
            try {
                session.start()
                connections[connectionId] = login to session
                outbound.send(WebSyncEvent.Connection(connectionId))
                launch { session.snackbars.collect { outbound.send(WebSyncEvent.Snackbar(it)) } }
                launch { session.scrollRequests.collect { outbound.send(it) } }
                // La sesión decide qué está abierto; un movimiento del runtime (send de New Chat,
                // borrado) se anuncia antes de cualquier evento del target nuevo.
                launch {
                    session.openTarget.collectLatest { target ->
                        if (target.movedByServer) {
                            outbound.send(WebSyncEvent.Opened(target.conversationId, target.browserSeq))
                        }
                        coroutineScope {
                            launch {
                                session.composerState
                                    .filter { it.conversationId == target.conversationId && it.seq == target.browserSeq }
                                    .map {
                                        WebSyncEvent.Composer(
                                            it.conversationId, it.snapshot.phase.name, it.snapshot.acceptedVersion,
                                            it.seq, it.text, it.editRevision, it.actionId, it.modelValid,
                                            it.generating, it.stopping,
                                            it.modelId, it.models, it.queue.map { queued ->
                                                buildJsonObject {
                                                    put("id", queued.id)
                                                    put("text", queued.text)
                                                    put("attachmentCount", queued.attachments.size)
                                                }
                                            },
                                            it.attachments.map { attachment ->
                                                buildJsonObject {
                                                    put("id", attachment.localId)
                                                    put("type", attachment.type)
                                                    put("name", attachment.fileName)
                                                    put("state", attachment.importState.name)
                                                    put("storage", attachment.storage.name)
                                                    put("unavailable", attachment.unavailable)
                                                    put("pageCount", attachment.pageCount)
                                                    put("durationMs", attachment.videoDurationMs)
                                                    put("frameCount", attachment.frameCount)
                                                    put("intervalMs", attachment.sliceIntervalMs)
                                                    put("staged", attachment.localPath != null)
                                                    if (attachment.type == "file" && attachment.importState == com.newoether.agora.model.AttachmentImportState.READY &&
                                                        attachment.storage == com.newoether.agora.model.AttachmentStorage.APP_PRIVATE && !attachment.unavailable) {
                                                        put("text", attachment.preparedText)
                                                    }
                                                    attachment.selectedPages?.let { pages ->
                                                        put("selectedPages", JsonArray(pages.sorted().map(::JsonPrimitive)))
                                                    }
                                                    put("pagePreviewCount", attachment.preRenderedPaths?.size ?: 0)
                                                    put("framePreviewCount", attachment.processedFrames?.size ?: 0)
                                                    attachment.videoDurationMs?.let { duration ->
                                                        put("defaultFrameCount", com.newoether.agora.ui.chat.VideoSliceDefaults.defaultFrameCount(duration))
                                                    }
                                                    it.pdfProgress[attachment.localId]?.let { (done, total) ->
                                                        put("previewDone", done)
                                                        put("previewTotal", total)
                                                    }
                                                }
                                            },
                                        )
                                    }.distinctUntilChanged().collect { outbound.send(it) }
                            }
                            target.conversationId?.let { id ->
                                openConversation(id, list.map { it.first }, watched, outbound)
                            }
                        }
                    }
                }
                for (text in incoming) {
                    val command = runCatching {
                        json.decodeFromString(WebSyncCommand.serializer(), text)
                    }.getOrNull() ?: continue
                    when (command.type) {
                        COMMAND_OPEN -> {
                            watched.value = emptySet()
                            session.open(command.conversationId, command.seq)
                        }
                        COMMAND_WATCH -> watched.value = command.messageIds.take(MAX_WATCHED).toSet()
                        COMMAND_DRAFT -> session.edit(command.text.orEmpty(), command.revision, command.seq)
                        COMMAND_SEND -> session.send(command.text.orEmpty(), command.seq, command.actionId)
                        COMMAND_CANCEL_WAITING -> session.cancelWaiting(command.seq, command.actionId)
                        COMMAND_STOP -> session.stop(command.seq)
                        COMMAND_MODEL -> session.selectModel(command.modelId.orEmpty(), command.seq, command.actionId)
                        COMMAND_REMOVE_QUEUED -> session.removeQueued(command.queuedId.orEmpty(), command.seq)
                        COMMAND_SEND_QUEUED -> session.sendQueued(command.seq, command.actionId)
                        "attachment_remove", "attachment_retry", "attachment_pdf", "attachment_video" ->
                            session.attachmentCommand(command)
                    }
                }
            } finally {
                connections.remove(connectionId)
                withContext(kotlinx.coroutines.NonCancellable) {
                    // Los hijos de la conexión (uploads en vuelo) se asientan ANTES del reclaim
                    // del close: ningún importer ni Send sobrevive a la reclamación de archivos.
                    session.endUploads()
                    val children = connectionJob.children.toList()
                    children.forEach { it.cancel() }
                    children.forEach { it.join() }
                    session.close()
                }
            }
            coroutineContext.cancelChildren()
        }

    private suspend fun openConversation(
        id: String,
        list: Flow<List<ChatConversation>>,
        watched: StateFlow<Set<String>>,
        outbound: SendChannel<WebSyncEvent>,
    ) {
        // La web solo puede abrir lo que su lista mostró; una fila que ya no existe fue borrada.
        if (list.first().none { it.id == id }) {
            outbound.send(WebSyncEvent.Deleted(id))
            return
        }
        // Un fallo en cualquier collector termina solo esta conversación, jamás la conexión.
        try {
            coroutineScope { observeConversation(id, list, watched, outbound) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DebugLog.e(TAG, "WebUI failed to load a conversation", error)
            outbound.send(WebSyncEvent.LoadFailed(id))
        }
    }

    private suspend fun CoroutineScope.observeConversation(
        id: String,
        list: Flow<List<ChatConversation>>,
        watched: StateFlow<Set<String>>,
        outbound: SendChannel<WebSyncEvent>,
    ) {
        executionCoordinator.tryWithConversationLock(id) {
            conversations.recoverConversationRuntime(id)
        }
        val state = registry.getOrCreate(id)
        val selectedChildren = list.map { items ->
            val row = items.firstOrNull { it.id == id }
            if (row == null) {
                outbound.send(WebSyncEvent.Deleted(id))
                this@observeConversation.cancel()
            }
            decodeSelectedChildren(row?.selectedBranchesJson)
        }.distinctUntilChanged()
        val stubs = conversations.observeMessageTopology(id)
            .distinctUntilChanged()
            .map { topology -> topology.map { it.toUiChatMessageStub() } }
        val pathIds = MutableStateFlow<Set<String>>(emptySet())
        launch {
            combine(stubs, selectedChildren, state.generationSnapshot) { all, selected, snapshot ->
                val path = withContext(projectionDispatcher) {
                    ConversationUiState.resolvePath(all, snapshot.streamingMessage, selected)
                }
                WebSyncEvent.Path(
                    conversationId = id,
                    messages = path.map { it.toWebPathEntry() },
                    generating = snapshot.isGenerating,
                )
            }
                .distinctUntilChanged()
                .collect { event ->
                    pathIds.value = event.messages.mapTo(mutableSetOf()) { it.id }
                    outbound.send(event)
                }
        }
        launch {
            state.generationSnapshot
                .map { it.streamingMessage }
                .distinctUntilChanged()
                .mapLatest { message -> message?.let { project(it) } }
                .collect { outbound.send(WebSyncEvent.Streaming(id, it)) }
        }
        launch { servePayloads(id, watched, pathIds, outbound) }
    }

    /** Una suscripción de payload por fila observada; solo filas del path actual se sirven. */
    private suspend fun servePayloads(
        conversationId: String,
        watched: StateFlow<Set<String>>,
        pathIds: StateFlow<Set<String>>,
        outbound: SendChannel<WebSyncEvent>,
    ) = coroutineScope {
        val jobs = mutableMapOf<String, Job>()
        combine(watched, pathIds) { ids, path -> ids intersect path }
            .distinctUntilChanged()
            .collect { ids ->
                (jobs.keys - ids).forEach { jobs.remove(it)?.cancel() }
                (ids - jobs.keys).forEach { messageId ->
                    jobs[messageId] = launch {
                        hydration.observeMessage(messageId) { it.forDisplay(customProviders.value) }
                            .distinctUntilChanged()
                            .collect { message ->
                                if (message != null) {
                                    outbound.send(WebSyncEvent.Payload(conversationId, project(message)))
                                }
                            }
                    }
                }
            }
    }

    private suspend fun project(message: ChatMessage): WebMessage =
        withContext(projectionDispatcher) {
            message.forDisplay(customProviders.value).toWeb(parseInlineDollarMath.value)
        }

    companion object {
        private const val TAG = "WebUiSync"
        const val COMMAND_OPEN = "open"
        const val COMMAND_WATCH = "watch"
        const val COMMAND_SEND = "send"
        const val COMMAND_STOP = "stop"
        const val COMMAND_DRAFT = "draft"
        const val COMMAND_CANCEL_WAITING = "cancel_waiting"
        const val COMMAND_MODEL = "model"
        const val COMMAND_REMOVE_QUEUED = "remove_queued"
        const val COMMAND_SEND_QUEUED = "send_queued"

        /** Tope de filas a las que un navegador puede suscribirse a la vez. */
        const val MAX_WATCHED = 48

        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            classDiscriminator = "type"
        }

        /** Misma decodificación que el assembler de la app: la key `"null"` es la raíz. */
        internal fun decodeSelectedChildren(raw: String?): Map<String?, String> =
            raw?.let {
                runCatching {
                    json.decodeFromString<Map<String, String>>(it)
                        .mapKeys { (key, _) -> if (key == "null") null else key }
                }.getOrNull()
            }.orEmpty()
    }
}

/** Un math span en [WebText.markdown] es `MATH_OPEN + index + MATH_CLOSE`. */
internal const val MATH_OPEN = '\uE000'
internal const val MATH_CLOSE = '\uE001'

/**
 * Markdown con el math ya separado por el [parseLatexSpans] de la app, así el navegador detecta
 * fórmulas exactamente como la app. Los caracteres private-use ya presentes en el texto se
 * reemplazan para que no puedan confundirse con un placeholder.
 */
internal fun String.toWebText(parseInlineDollarMath: Boolean): WebText {
    val spans = parseLatexSpans(this, parseInlineDollarMath)
    val math = mutableListOf<WebMath>()
    val markdown = buildString {
        spans.forEach { span ->
            if (span.isLatex) {
                append(MATH_OPEN).append(math.size).append(MATH_CLOSE)
                math += WebMath(span.content, span.display)
            } else {
                append(span.content.replace(MATH_OPEN, '\uFFFD').replace(MATH_CLOSE, '\uFFFD'))
            }
        }
    }
    return WebText(markdown, math)
}

private fun ChatConversation.toWeb(generating: Boolean) = WebConversation(
    id = id,
    title = title,
    generating = generating,
    unread = hasUnreadGeneration,
)

private fun ChatMessage.toWebPathEntry() = WebPathEntry(
    id = id,
    parentId = parentId,
    participant = participant.name,
    status = status.name,
)

private fun ChatMessage.toWeb(inlineDollarMath: Boolean) = WebMessage(
    id = id,
    parentId = parentId,
    participant = participant.name,
    status = status.name,
    timestamp = timestamp,
    modelName = modelName,
    // El bubble del usuario muestra texto plano, así su math no se separa.
    text = if (participant == Participant.USER) WebText(text) else text.toWebText(inlineDollarMath),
    thoughts = thoughts?.toWebText(inlineDollarMath),
    thoughtTitle = thoughtTitle,
    thoughtTimeMs = thoughtTimeMs,
    segments = segments.orEmpty().map { it.toWeb(inlineDollarMath) },
    // Shim: el frontend a18eb53e solo pinta el cuerpo del modelo desde presentation.answer
    // (messages.js ModelMessage). Sin el presentation block completo, el fallback usaTimeline=false
    // renderiza answer como Markdown directo.
    presentation = if (participant == Participant.USER) null else WebPresentation(
        useTimeline = false,
        answer = text.toWebText(inlineDollarMath),
    ),
)

private fun MessageSegment.toWeb(inlineDollarMath: Boolean) = WebSegment(
    type = type,
    content = content.toWebText(inlineDollarMath),
    durationMs = durationMs,
    toolName = toolName,
    toolDisplayName = toolDisplayName,
    toolState = toolState,
    errorCode = errorCode,
)

@Serializable
internal data class WebSyncCommand(
    val type: String,
    val conversationId: String? = null,
    val messageIds: List<String> = emptyList(),
    /** La secuencia del request open del navegador, repetida en [WebSyncEvent.Opened]. */
    val seq: Long = 0L,
    val text: String? = null,
    val revision: Long = 0L,
    val actionId: Long = 0L,
    val modelId: String? = null,
    val queuedId: String? = null,
    /** Comandos de attachment: el id del attachment en el draft del composer. */
    val attachmentId: String? = null,
    val pages: List<Int> = emptyList(),
    val frameCount: Int? = null,
    val intervalMs: Long? = null,
)

@Serializable
internal sealed interface WebSyncEvent {
    /** Identifica esta conexión para los POST de upload; viaja primero, tras el display. */
    @Serializable @SerialName("connection")
    data class Connection(val connectionId: String) : WebSyncEvent

    @Serializable @SerialName("conversations")
    data class Conversations(val items: List<WebConversation>) : WebSyncEvent

    /** La sesión siguió un movimiento del runtime; [seq] es el open request del navegador que sigue vigente. */
    @Serializable @SerialName("opened")
    data class Opened(val conversationId: String?, val seq: Long) : WebSyncEvent

    /** La rama seleccionada de la conversación abierta, sin cuerpos de mensajes. */
    @Serializable @SerialName("path")
    data class Path(
        val conversationId: String,
        val messages: List<WebPathEntry>,
        val generating: Boolean,
    ) : WebSyncEvent

    /** El cuerpo de una fila observada. */
    @Serializable @SerialName("payload")
    data class Payload(val conversationId: String, val message: WebMessage) : WebSyncEvent

    /** El mensaje en vuelo; reemplaza el cuerpo durable de la fila con el mismo id. */
    @Serializable @SerialName("streaming")
    data class Streaming(val conversationId: String, val message: WebMessage?) : WebSyncEvent

    @Serializable @SerialName("deleted")
    data class Deleted(val conversationId: String) : WebSyncEvent

    @Serializable @SerialName("load_failed")
    data class LoadFailed(val conversationId: String) : WebSyncEvent

    /** Fase de submisión del composer que el navegador muestra; [acceptedVersion] crece por send aceptado. */
    @Serializable @SerialName("composer")
    data class Composer(
        val conversationId: String?, val phase: String, val acceptedVersion: Long,
        val seq: Long, val text: String, val editRevision: Long, val actionId: Long,
        val modelValid: Boolean, val generating: Boolean, val stopping: Boolean,
        /** Modelo activo del target y el catálogo etiquetado que el selector del navegador lista. */
        val modelId: String, val models: Map<String, String>,
        /** Cola de guidance pendiente: id, texto y conteo de attachments por entrada. */
        val queue: List<JsonObject>,
        /** Proyección de attachments del draft: id, tipo, estado, configuración y previews. */
        val attachments: List<JsonObject> = emptyList(),
    ) : WebSyncEvent

    @Serializable @SerialName("snackbar")
    data class Snackbar(val message: String) : WebSyncEvent

    @Serializable @SerialName("scroll_to_bottom")
    data class ScrollToBottom(val conversationId: String, val messageId: String, val seq: Long) : WebSyncEvent

    /**
     * Shim del presentation block (c6b2c8ef, diferido): el frontend lee display.reduceMotion SIN
     * optional chaining en el menú del selector (shell.js:440) y crashea si el evento no llega.
     * Los modos de tools/thinking alimentan los títulos de cards cuando llegue el bloque real.
     */
    @Serializable @SerialName("display")
    data class Display(
        val toolCallDisplayMode: String,
        val thinkingSegmentDisplayMode: String,
        val autoExpandActiveGroup: Boolean,
        val autoWrapCodeBlocks: Boolean,
        val blurEffectsEnabled: Boolean,
        val reduceMotion: Boolean,
        val liveThinking: WebLiveTimerStrings,
    ) : WebSyncEvent
}

/** Plantillas de los timers de thinking; el navegador formatea los segundos corriendo. */
@Serializable
internal data class WebLiveTimerStrings(
    val seconds: String,
    val minutes: String,
    val hours: String,
)

@Serializable
internal data class WebConversation(
    val id: String,
    val title: String,
    val generating: Boolean,
    val unread: Boolean,
)

@Serializable
internal data class WebPathEntry(
    val id: String,
    val parentId: String?,
    val participant: String,
    val status: String,
)

@Serializable
internal data class WebMessage(
    val id: String,
    val parentId: String?,
    val participant: String,
    val status: String,
    val timestamp: Long,
    val modelName: String?,
    val text: WebText,
    val thoughts: WebText?,
    val thoughtTitle: String?,
    val thoughtTimeMs: Long?,
    val segments: List<WebSegment>,
    /**
     * Shim de presentación del bloque upstream c6b2c8ef (diferido): el frontend exige este
     * campo para pintar el cuerpo del modelo. Con `useTimeline=false` consume solo [answer]
     * como Markdown directo; timeline/blocks/compact llegarán con el presentation block.
     */
    val presentation: WebPresentation? = null,
)

/**
 * Presentación mínima que satisface el contrato del frontend a18eb53e sin el modelo completo
 * de bloques (upstream c6b2c8ef): `presentation?.useTimeline` falso toma el fallback que
 * renderiza `answer` con el componente Markdown; `compact`/`blocks` ausentes se saltan.
 */
@Serializable
internal data class WebPresentation(
    val useTimeline: Boolean = false,
    val answer: WebText? = null,
)

@Serializable
internal data class WebSegment(
    val type: String,
    val content: WebText,
    val durationMs: Long?,
    val toolName: String?,
    val toolDisplayName: String?,
    val toolState: String?,
    val errorCode: String?,
)

@Serializable
internal data class WebText(val markdown: String, val math: List<WebMath> = emptyList())

@Serializable
internal data class WebMath(val tex: String, val display: Boolean)
