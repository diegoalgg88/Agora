package com.newoether.agora.webui

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.forDisplay
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    private val projectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Sirve una conexión hasta que [incoming] cierra. Los comandos llegan como texto JSON; cada
     * evento se entrega a [send] desde una sola coroutine, así [send] no necesita lock propio.
     */
    suspend fun serve(incoming: ReceiveChannel<String>, send: suspend (String) -> Unit) =
        coroutineScope {
            // Rendezvous: un productor espera a que el sender tome su evento, así un navegador
            // lento frena a los productores en vez de inflar una cola.
            val outbound = Channel<WebSyncEvent>()
            launch { for (event in outbound) send(json.encodeToString(WebSyncEvent.serializer(), event)) }
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
            var open: Job? = null
            for (text in incoming) {
                val command = runCatching {
                    json.decodeFromString(WebSyncCommand.serializer(), text)
                }.getOrNull() ?: continue
                when (command.type) {
                    COMMAND_OPEN -> {
                        open?.cancelAndJoin()
                        watched.value = emptySet()
                        open = command.conversationId?.let { id ->
                            launch {
                                openConversation(id, list.map { it.first }, watched, outbound)
                            }
                        }
                    }
                    COMMAND_WATCH -> watched.value = command.messageIds.take(MAX_WATCHED).toSet()
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
    text = text.toWebText(inlineDollarMath),
    thoughts = thoughts?.toWebText(inlineDollarMath),
    thoughtTitle = thoughtTitle,
    thoughtTimeMs = thoughtTimeMs,
    segments = segments.orEmpty().map { it.toWeb(inlineDollarMath) },
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
)

@Serializable
internal sealed interface WebSyncEvent {
    @Serializable @SerialName("conversations")
    data class Conversations(val items: List<WebConversation>) : WebSyncEvent

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
}

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
