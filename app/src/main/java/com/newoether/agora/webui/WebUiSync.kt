package com.newoether.agora.webui

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.forDisplay
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.observeConversationTodo
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import kotlinx.coroutines.flow.flatMapLatest
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
    /**
     * Contexto de visualización del navegador: settings de apariencia + idioma de la app. El
     * evento Display viaja primero y con replay=1: el Composer del frontend lee
     * display.reduceMotion sin optional chaining (shell.js:440) y crashea si aún no llegó.
     */
    private val display: Flow<WebDisplayContext> = emptyFlow(),
    /** Construye el [WebUiChatSession] de una conexión con el scope de esa conexión. */
    private val openChatSession: (CoroutineScope) -> WebUiChatSession,
    /** Preguntas ask_user activas; el navegador responde vía question_submit/skip. */
    private val askUser: com.newoether.agora.viewmodel.AskUserController? = null,
    /** Confirmaciones de shell pendientes; el navegador responde vía shell_decision. */
    private val shellConfirmation: com.newoether.agora.viewmodel.ShellConfirmationController? = null,
    /** Fork/share identificados hacia el navegador; null deshabilita esos comandos. */
    private val forkShare: com.newoether.agora.viewmodel.ConversationForkShareController? = null,
    /** El único owner de metadata de conversación que el teléfono también usa; el menú del drawer va por él. */
    private val conversationLifecycle: com.newoether.agora.viewmodel.ConversationLifecycleController? = null,
    /** Búsqueda global de conversaciones; devuelve mensaje + score por match. */
    private val search: suspend (String) -> List<Pair<com.newoether.agora.data.local.MessageEntity, Float>> = { emptyList() },
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
            // El drawer pagina con la proyección angosta del DAO; el límite crece con list_more.
            val listLimit = MutableStateFlow(DRAWER_PAGE_SIZE)
            val list = combine(
                listLimit.flatMapLatest { limit ->
                    conversations.observeDrawerConversations(limit + 1).map { rows -> limit to rows }
                },
                registry.activeConversationIds,
            ) { (limit, rows), active ->
                WebSyncEvent.Conversations(
                    rows.take(limit).map {
                        WebConversation(it.id, it.title, it.id in active, it.hasUnreadGeneration, it.isPinned)
                    },
                    hasMore = rows.size > limit,
                    limit = limit,
                )
            }.shareIn(this, SharingStarted.Eagerly, replay = 1)
            launch { list.distinctUntilChanged().collect { outbound.send(it) } }
            val displayContext = display.distinctUntilChanged()
                .shareIn(this, SharingStarted.Eagerly, replay = 1)
            launch { displayContext.collect { outbound.send(it.toEvent()) } }
            val watched = MutableStateFlow<Set<String>>(emptySet())
            val session = openChatSession(this)
            // Búsqueda dentro de la conversación abierta; revisión creciente descarta viejas.
            val conversationQuery = MutableStateFlow<WebSyncCommand?>(null)
            // Búsqueda global: revisión creciente y un solo job vivo por conexión.
            val searchRevision = MutableStateFlow(0L)
            var searchJob: Job? = null
            // Los comandos de fila resuelven dentro de las filas que esta conexión muestra.
            val visiblePath = MutableStateFlow<List<ChatMessage>>(emptyList())
            val pendingPageAction = java.util.concurrent.atomic.AtomicReference<WebSyncCommand?>(null)
            var lastPageActionId = 0L
            val connectionId = java.util.UUID.randomUUID().toString()
            val connectionJob = coroutineContext[Job]!!
            try {
                session.start()
                connections[connectionId] = login to session
                outbound.send(WebSyncEvent.Connection(connectionId))
                launch { session.snackbars.collect { outbound.send(WebSyncEvent.Snackbar(it)) } }
                launch { session.scrollRequests.collect { outbound.send(it) } }
                // Interacciones visibles para la conversación que este navegador muestra: solo
                // esa conversación ve sus preguntas; una global siempre es visible.
                val interactionActionId = MutableStateFlow(0L)
                if (askUser != null && shellConfirmation != null) {
                    launch {
                        combine(
                            session.openTarget, askUser.requests, shellConfirmation.pendingShellCommand, interactionActionId,
                        ) { target, questions, shell, actionId ->
                            val visible = com.newoether.agora.ui.chat.interaction.userInteractions(
                                target.conversationId, questions, shell,
                            )
                            WebSyncEvent.Interactions(
                                target.conversationId, target.browserSeq, actionId,
                                visible.map { request -> when (request) {
                                    is com.newoether.agora.ui.chat.interaction.UserInteraction.Question ->
                                        buildJsonObject {
                                            put("kind", "question")
                                            put("id", request.request.id.toString())
                                            put("question", request.request.question)
                                            put("options", JsonArray(request.request.options.map(::JsonPrimitive)))
                                            put("allowMultiple", request.request.allowMultiple)
                                        }
                                    is com.newoether.agora.ui.chat.interaction.UserInteraction.ShellCommand ->
                                        buildJsonObject {
                                            put("kind", "shell")
                                            put("id", request.pending.id.toString())
                                            put("server", request.pending.server)
                                            put("summary", request.pending.summary)
                                        }
                                } },
                            )
                        }.distinctUntilChanged().collect { outbound.send(it) }
                    }
                }
                // La sesión decide qué está abierto; un movimiento del runtime (send de New Chat,
                // borrado) se anuncia antes de cualquier evento del target nuevo.
                launch {
                    session.openTarget.collectLatest { target ->
                        conversationQuery.value = null
                        if (target.movedByServer) {
                            outbound.send(WebSyncEvent.Opened(target.conversationId, target.browserSeq))
                        }
                        coroutineScope {
                            // El todo pertenece al target: solo esa conversación se observa y su
                            // secuencia viaja para que el navegador descarte la del target viejo.
                            launch {
                                if (target.conversationId == null) {
                                    outbound.send(WebSyncEvent.Todo(null, target.browserSeq, emptyList()))
                                } else {
                                    conversations.observeConversationTodo(target.conversationId).collect {
                                        outbound.send(WebSyncEvent.Todo(target.conversationId, target.browserSeq, it))
                                    }
                                }
                            }
                            launch {
                                session.composerState
                                    .combine(customProviders) { state, _ -> state }
                                    .filter { it.conversationId == target.conversationId && it.seq == target.browserSeq }
                                    .map { projectComposerEvent(it, customProviders.value, json) }
                                    .distinctUntilChanged().collect { outbound.send(it) }
                            }
                            target.conversationId?.let { id ->
                                openConversation(
                                    id, session, watched, displayContext, outbound,
                                    connectionId, target.browserSeq, conversationQuery, visiblePath,
                                )
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
                        "pin" -> {
                            val id = command.conversationId ?: continue
                            val pinned = command.enabled ?: continue
                            conversationLifecycle?.setPinned(id, pinned)
                                ?: conversations.setConversationPinned(id, pinned)
                        }
                        "conversation_rename" -> {
                            val id = command.conversationId ?: continue
                            val title = command.text?.trim().orEmpty()
                            // Como el ChatRenameDialog del teléfono: un título vacío jamás escribe.
                            if (title.isNotEmpty()) conversationLifecycle?.rename(id, title)
                        }
                        "conversation_delete" -> {
                            val id = command.conversationId ?: continue
                            val actionId = command.actionId
                            if (actionId <= 0L) continue
                            val lifecycle = conversationLifecycle
                            if (lifecycle == null) {
                                outbound.send(WebSyncEvent.ConversationDelete(id, actionId, false))
                                continue
                            }
                            // La transacción del teléfono: rechazada con un send admitido, y cada
                            // cliente que aún muestre la conversación cae a New Chat. Esta sesión
                            // es el origen; solo su propio navegador settlea el diálogo que abrió.
                            val accepted = lifecycle.delete(session, id) { deleted ->
                                if (connectionJob.isActive) launch {
                                    outbound.send(WebSyncEvent.ConversationDelete(id, actionId, deleted))
                                }
                            }
                            if (!accepted) outbound.send(WebSyncEvent.ConversationDelete(id, actionId, false))
                        }
                        "question_submit", "question_skip", "shell_decision" -> {
                            val target = session.openTarget.value
                            if (askUser == null || shellConfirmation == null ||
                                command.connectionId.isBlank() || command.seq != target.browserSeq ||
                                command.conversationId != target.conversationId ||
                                command.actionId <= interactionActionId.value) continue
                            // La acción se consume identificada aunque el request desaparezca en
                            // otro cliente: el actionId evita doble submit.
                            interactionActionId.value = command.actionId
                            val visibleQuestions = askUser.requests.value.filter {
                                it.conversationId == null || it.conversationId == target.conversationId
                            }
                            when (command.type) {
                                "question_submit" -> {
                                    val answers = command.answers ?: continue
                                    if (answers.isEmpty()) continue
                                    val waiting = visibleQuestions.associateBy { it.id.toString() }
                                    if (answers.any { (id, answer) ->
                                        val request = waiting[id]
                                        request == null || answer.choices.distinct().size != answer.choices.size ||
                                            answer.choices.any { it !in request.options } ||
                                            (!request.allowMultiple && answer.choices.size > 1)
                                    }) continue
                                    answers.forEach { (id, answer) -> askUser.submit(id.toLong(), answer.choices) }
                                }
                                "question_skip" -> {
                                    val request = visibleQuestions.firstOrNull {
                                        it.id.toString() == command.requestId
                                    } ?: continue
                                    askUser.dismiss(request.id)
                                }
                                "shell_decision" -> {
                                    val pending = shellConfirmation.pendingShellCommand.value ?: continue
                                    if (pending.id.toString() != command.requestId ||
                                        (pending.conversationId != null &&
                                            pending.conversationId != target.conversationId)) continue
                                    shellConfirmation.resolve(command.enabled ?: continue, command.alwaysAllow == true)
                                }
                            }
                        }
                        "conversation_search" -> {
                            // La búsqueda dentro de la conversación abierta la maneja el job de
                            // observeConversation vía conversationQuery; aquí solo se admite.
                            conversationQuery.value = command
                        }
                        "search" -> {
                            if (command.connectionId != connectionId ||
                                command.revision <= searchRevision.value) continue
                            searchRevision.value = command.revision
                            searchJob?.cancel()
                            val query = command.text.orEmpty()
                            searchJob = launch(projectionDispatcher) {
                                if (query.isBlank()) {
                                    outbound.send(WebSyncEvent.Search(connectionId, command.revision, query, false))
                                    return@launch
                                }
                                outbound.send(WebSyncEvent.Search(connectionId, command.revision, query, true))
                                try {
                                    kotlinx.coroutines.delay(200)
                                    val matches = search(query).take(20)
                                    currentCoroutineContext().ensureActive()
                                    val titles = conversations.getDrawerConversations(
                                        matches.map { it.first.conversationId }.distinct(),
                                    ).associate { it.id to it.title }
                                    val providers = customProviders.value
                                    val items = matches.groupBy { it.first.conversationId }.mapNotNull { (id, entries) ->
                                        val title = titles[id] ?: return@mapNotNull null
                                        buildJsonObject {
                                            put("id", id)
                                            put("title", com.newoether.agora.data.replaceCustomProviderIdsForDisplay(title, providers))
                                            put("score", entries.maxOf { it.second })
                                            put("snippets", JsonArray(entries.take(2).map { (message, _) ->
                                                val text = com.newoether.agora.data.replaceCustomProviderIdsForDisplay(message.text, providers)
                                                val index = text.indexOf(query, ignoreCase = true)
                                                val start = if (index < 0) 0 else (index - 20).coerceAtLeast(0)
                                                val end = if (index < 0) text.length else (index + query.length + 20).coerceAtMost(text.length)
                                                buildJsonObject {
                                                    put("role", message.participant.name)
                                                    put("text", (if (start > 0) "…" else "") + text.substring(start, end) +
                                                        (if (end < text.length) "…" else ""))
                                                }
                                            }))
                                        }
                                    }
                                    if (searchRevision.value == command.revision) {
                                        outbound.send(WebSyncEvent.Search(connectionId, command.revision, query, false, items))
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    if (searchRevision.value == command.revision) {
                                        outbound.send(WebSyncEvent.Search(connectionId, command.revision, query, false, failed = true))
                                    }
                                }
                            }
                        }
                        "list_more" -> if (command.tokens == listLimit.value.toLong()) {
                            listLimit.value += DRAWER_PAGE_SIZE
                        }
                        "edit", "regenerate", "delete" -> session.rowCommand(command, visiblePath.value)
                        "fork", "share" -> {
                            val target = session.openTarget.value
                            if (forkShare == null ||
                                target.conversationId == null || target.conversationId != command.conversationId ||
                                target.browserSeq != command.seq || command.actionId <= lastPageActionId) continue
                            val pending = pendingPageAction.get()
                            if (pending != null && pending.seq == target.browserSeq &&
                                pending.conversationId == target.conversationId) continue
                            lastPageActionId = command.actionId
                            pendingPageAction.set(command)
                            val isCurrent = { connectionJob.isActive && session.openTarget.value == target }
                            val complete: (Boolean, String?) -> Unit = { success, shared ->
                                if (pendingPageAction.compareAndSet(command, null) && connectionJob.isActive) launch {
                                    outbound.send(
                                        WebSyncEvent.PageAction(
                                            command.conversationId!!, command.seq,
                                            command.actionId, command.type, success, shared,
                                        ),
                                    )
                                }
                            }
                            // La fila nombra su propio Fork/Share; el top bar no envía ninguna,
                            // así que forkear y compartir la conversación completa sigue igual.
                            val started = when {
                                command.type == "fork" -> forkShare.fork(
                                    session, command.messageId,
                                    isCurrent = isCurrent,
                                    openFork = { session.openForkIfCurrent(it, target) },
                                    onResult = { complete(it, null) },
                                )
                                command.messageId != null -> forkShare.shareGeneration(
                                    session, command.messageId, isCurrent,
                                ) { complete(it != null, it) }
                                else -> forkShare.shareConversation(
                                    session, isCurrent,
                                ) { complete(it != null, it) }
                            }
                            if (!started) complete(false, null)
                        }
                        "attachment_remove", "attachment_retry", "attachment_pdf", "attachment_video" ->
                            session.attachmentCommand(command)
                        "setting" -> session.settingCommand(command)
                        "advanced", "compact" -> session.editorCommand(command)
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
        session: WebUiChatSession,
        watched: StateFlow<Set<String>>,
        display: Flow<WebDisplayContext>,
        outbound: SendChannel<WebSyncEvent>,
        connectionId: String,
        seq: Long,
        query: StateFlow<WebSyncCommand?>,
        visiblePath: MutableStateFlow<List<ChatMessage>>,
    ) {
        val selected = conversations.observeConversation(id)
        // La web solo puede abrir lo que existe; una fila que ya no existe fue borrada.
        if (selected.first() == null) {
            outbound.send(WebSyncEvent.Deleted(id))
            return
        }
        // Un fallo en cualquier collector termina solo esta conversación, jamás la conexión.
        try {
            coroutineScope {
                observeConversation(
                    id, session, selected, visiblePath, watched, display, outbound,
                    connectionId, seq, query,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DebugLog.e(TAG, "WebUI failed to load a conversation", error)
            outbound.send(WebSyncEvent.LoadFailed(id))
        }
    }

    private suspend fun CoroutineScope.observeConversation(
        id: String,
        session: WebUiChatSession,
        selected: Flow<ChatConversation?>,
        visiblePath: MutableStateFlow<List<ChatMessage>>,
        watched: StateFlow<Set<String>>,
        display: Flow<WebDisplayContext>,
        outbound: SendChannel<WebSyncEvent>,
        connectionId: String,
        seq: Long,
        query: StateFlow<WebSyncCommand?>,
    ) {
        executionCoordinator.tryWithConversationLock(id) {
            conversations.recoverConversationRuntime(id)
        }
        val state = registry.getOrCreate(id)
        val selectedChildren = selected.map { row ->
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
        // Mensajes del path elegibles para búsqueda: USER/MODEL reales, sin tool/result/compact.
        val searchIds = MutableStateFlow<List<String>>(emptyList())
        launch {
            combine(stubs, selectedChildren, state.generationSnapshot) { all, selected, snapshot ->
                val path = withContext(projectionDispatcher) {
                    ConversationUiState.resolvePath(all, snapshot.streamingMessage, selected)
                }
                // Los comandos de fila resuelven el límite de generación del teléfono dentro
                // de las filas que este navegador muestra.
                visiblePath.value = path
                WebSyncEvent.Path(
                    conversationId = id,
                    messages = path.map { it.toWebPathEntry() },
                    generating = snapshot.isGenerating,
                )
            }
                .distinctUntilChanged()
                .collect { event ->
                    pathIds.value = event.messages.mapTo(mutableSetOf()) { it.id }
                    searchIds.value = event.messages.filter {
                        (it.participant == "USER" || it.participant == "MODEL") &&
                            !it.id.startsWith(com.newoether.agora.util.Constants.TOOL_MSG_PREFIX) &&
                            !it.id.startsWith(com.newoether.agora.util.Constants.RESULT_MSG_PREFIX) &&
                            !it.id.startsWith(com.newoether.agora.util.Constants.COMPACT_MSG_PREFIX)
                    }.map { it.id }
                    outbound.send(event)
                }
        }
        launch {
            state.generationSnapshot
                .map { it.streamingMessage }
                .distinctUntilChanged()
                .combine(display) { message, context -> message to context }
                .mapLatest { (message, context) ->
                    message?.let { project(it, isStreaming = true, context) }
                }
                .collect { outbound.send(WebSyncEvent.Streaming(id, it)) }
        }
        launch { servePayloads(id, watched, pathIds, display, outbound) }
        // Búsqueda dentro de la conversación abierta: revisión creciente, carga paginada y solo
        // mensajes del path visible; el streaming en vuelo se proyecta desde la snapshot.
        launch(projectionDispatcher) {
            combine(searchIds, query, customProviders, state.generationSnapshot) { ids, command, providers, snapshot ->
                Triple(ids, command, providers) to snapshot.streamingMessage
            }.collectLatest { (input, streaming) ->
                val (ids, command, providers) = input
                if (command == null || command.conversationId != id || command.seq != seq) return@collectLatest
                val text = command.text.orEmpty()
                suspend fun publish(searching: Boolean, matches: List<JsonObject> = emptyList(), failed: Boolean = false) {
                    outbound.send(WebSyncEvent.ConversationSearch(command.connectionId, id, seq, command.revision, text, searching, matches, failed))
                }
                if (text.isBlank()) { publish(false); return@collectLatest }
                publish(true)
                try {
                    val matches = com.newoether.agora.ui.chat.scanConversationSearchMatches(ids, text) { page ->
                        val rows = hydration.loadMessages(id, page) { it.forDisplay(providers) }
                        if (streaming == null || streaming.id !in page) rows
                        else rows.filterNot { it.id == streaming.id } + streaming.forDisplay(providers)
                    }.map { match -> buildJsonObject {
                        put("messageId", match.messageId)
                        put("start", match.start)
                        put("endExclusive", match.endExclusive)
                        put("occurrence", match.occurrenceInMessage)
                        put("key", match.key)
                    } }
                    currentCoroutineContext().ensureActive()
                    publish(false, matches)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    DebugLog.e(TAG, "WebUI conversation search failed", error)
                    publish(false, failed = true)
                }
            }
        }
    }

    /** Una suscripción de payload por fila observada; solo filas del path actual se sirven. */
    private suspend fun servePayloads(
        conversationId: String,
        watched: StateFlow<Set<String>>,
        pathIds: StateFlow<Set<String>>,
        display: Flow<WebDisplayContext>,
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
                            .combine(display) { message, context -> message to context }
                            .collect { (message, context) ->
                                if (message != null) {
                                    outbound.send(
                                        WebSyncEvent.Payload(
                                            conversationId,
                                            project(message, isStreaming = false, context),
                                        ),
                                    )
                                }
                            }
                    }
                }
            }
    }

    private suspend fun project(
        message: ChatMessage,
        isStreaming: Boolean,
        display: WebDisplayContext,
    ): WebMessage = withContext(projectionDispatcher) {
        val shown = message.forDisplay(customProviders.value)
        shown.toWeb(display.parseInlineDollarMath, webPresentation(shown, isStreaming, display))
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

        /** Página del drawer: cuántas filas trae cada consulta del navegador. */
        const val DRAWER_PAGE_SIZE = 80

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

private fun ChatMessage.toWeb(inlineDollarMath: Boolean, presentation: WebPresentation?) = WebMessage(
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
    presentation = presentation,
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
    /** Identifica la conexión que envía el comando; los comandos de otras conexiones se ignoran. */
    val connectionId: String = "",
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
    /** Pin de conversación: activar o desactivar. */
    val enabled: Boolean? = null,
    /** Comando setting: el control del tools menu que cambia. */
    val setting: String? = null,
    /** Comando setting: valor string (thinkingLevel/openAiServiceTier). */
    val value: String? = null,
    /** Editor advanced: el draft de los seis parámetros validado server-side. */
    val parameters: com.newoether.agora.data.ConversationSettings? = null,
    /** Editor compact: cuántos mensajes recientes retener. */
    val retainCount: Int? = null,
    /** Interacciones: id de la pregunta o shell pendiente que este comando resuelve. */
    val requestId: String? = null,
    /** Shell decision: confiar siempre en este server. */
    val alwaysAllow: Boolean? = null,
    /** Comandos de fila: la fila que edit/regenerate/delete nombra. */
    val messageId: String? = null,
    /** list_more: el límite de página que el navegador vio en el último evento. */
    val tokens: Long? = null,
    /** Interacciones: respuestas de question_submit, keyed por id de pregunta. */
    val answers: Map<String, QuestionAnswers>? = null,
) {
    /** Respuesta de una pregunta; [answered] distingue skip de submit vacío. */
    @Serializable
    data class QuestionAnswers(
        val choices: List<String> = emptyList(),
        val text: String? = null,
        val answered: Boolean = false,
    )
}

@Serializable
internal sealed interface WebSyncEvent {
    /** Identifica esta conexión para los POST de upload; viaja primero, tras el display. */
    @Serializable @SerialName("connection")
    data class Connection(val connectionId: String) : WebSyncEvent

    @Serializable @SerialName("conversations")
    data class Conversations(
        val items: List<WebConversation>,
        /** True cuando el query trajo más filas que el límite actual del drawer. */
        val hasMore: Boolean = false,
        /** El límite que produjo esta página; el navegador lo repite en list_more. */
        val limit: Int = 0,
    ) : WebSyncEvent

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

    /**
     * Cómo el owner de conversación del teléfono respondió al delete de ESTA conexión.
     * [actionId] repite el request: solo el navegador que preguntó settlea su propio diálogo;
     * un delete iniciado por otro cliente llega aquí como [Deleted] y una lista más corta.
     */
    @Serializable @SerialName("conversation_delete")
    data class ConversationDelete(
        val conversationId: String,
        val actionId: Long,
        val success: Boolean,
    ) : WebSyncEvent

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
        /** Controles efectivos + capabilities resueltas del modelo activo; null mientras carga. */
        val controls: JsonObject? = null,
        /** Overrides/defaults de los seis parámetros de generación + presets. */
        val advanced: JsonObject? = null,
        /** Configuración del editor manual de Compact; null en New Chat. */
        val compact: JsonObject? = null,
    ) : WebSyncEvent

    @Serializable @SerialName("snackbar")
    data class Snackbar(val message: String) : WebSyncEvent

    /**
     * Interacciones visibles para la conversación que este navegador muestra: preguntas ask_user
     * y confirmaciones de shell pendientes. [actionId] crece con cada acción consumida para que
     * el navegador descarte duplicados; [items] serializa kind/id/question/options por pregunta.
     */
    @Serializable @SerialName("interactions")
    data class Interactions(
        val conversationId: String?,
        val seq: Long,
        val actionId: Long,
        val items: List<JsonObject>,
    ) : WebSyncEvent

    /** Matches de búsqueda dentro de la conversación abierta; [revision] descarta respuestas viejas. */
    @Serializable @SerialName("conversation_search")
    data class ConversationSearch(
        val connectionId: String,
        val conversationId: String,
        val seq: Long,
        val revision: Long,
        val query: String,
        val searching: Boolean,
        val matches: List<JsonObject>? = null,
        val failed: Boolean = false,
    ) : WebSyncEvent

    /** Resultado identificado de un fork/share: el navegador descarta duplicados por actionId. */
    @Serializable @SerialName("page_action")
    data class PageAction(
        val conversationId: String,
        val seq: Long,
        val actionId: Long,
        val action: String,
        val success: Boolean,
        val sharedText: String? = null,
    ) : WebSyncEvent

    /** Resultado de la búsqueda global de conversaciones; [revision] descarta respuestas viejas. */
    @Serializable @SerialName("search")
    data class Search(
        val connectionId: String,
        val revision: Long,
        val query: String,
        val searching: Boolean,
        val items: List<JsonObject> = emptyList(),
        val failed: Boolean = false,
    ) : WebSyncEvent

    /** Todo de la conversación abierta; null en la lista significa que no hay lista. */
    @Serializable @SerialName("todo")
    data class Todo(
        val conversationId: String?,
        val seq: Long,
        val items: List<com.newoether.agora.model.ConversationTodoItem>,
    ) : WebSyncEvent

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

@Serializable
internal data class WebConversation(
    val id: String,
    val title: String,
    val generating: Boolean,
    val unread: Boolean,
    val isPinned: Boolean = false,
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
    /** Cómo la app acomoda un mensaje del modelo; null para mensajes del usuario. */
    val presentation: WebPresentation? = null,
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
