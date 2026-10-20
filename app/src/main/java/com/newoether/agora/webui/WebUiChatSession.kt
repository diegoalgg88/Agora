package com.newoether.agora.webui

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.modelDisplayName
import com.newoether.agora.data.providerDisplayName
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.ConversationSettingsTransferCoordinator
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.data.repository.updateConversationModel
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.apiModelName
import com.newoether.agora.viewmodel.AttachmentImportProcessor
import com.newoether.agora.viewmodel.BranchReplacementTransitionCoordinator
import com.newoether.agora.viewmodel.ChatClient
import com.newoether.agora.viewmodel.ChatClients
import com.newoether.agora.viewmodel.ComposerDraftController
import com.newoether.agora.viewmodel.ComposerDraftPersistence
import com.newoether.agora.viewmodel.ConversationComposerController
import com.newoether.agora.viewmodel.ConversationComposerSubmissionController
import com.newoether.agora.viewmodel.ConversationComposerSubmissionSnapshot
import com.newoether.agora.viewmodel.ConversationRenderStore
import com.newoether.agora.viewmodel.ConversationStateRegistry
import com.newoether.agora.viewmodel.ConversationUiStateAssembler
import com.newoether.agora.viewmodel.ConversationWorkspaceDraft
import com.newoether.agora.viewmodel.CurrentConversationRuntimeFacade
import com.newoether.agora.viewmodel.GenerationStopAdapter
import com.newoether.agora.viewmodel.MessageGenerationController
import com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID
import com.newoether.agora.viewmodel.NewChatWorkspaceSnapshot
import com.newoether.agora.viewmodel.QueuedSend
import com.newoether.agora.viewmodel.resolveValidModel
import com.newoether.agora.viewmodel.validChatModels
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One signed-in browser connection as a [ChatClient] of the process-scoped chat runtime.
 *
 * Send, Stop and queueing go through the same runtime owners the phone uses, so they work while
 * the app is in the background. The browser's open conversation, composer draft and New Chat
 * workspace belong to this session only (application-ui.md section 36): drafts stay in memory
 * and a New Chat send never touches the phone's persisted New Chat workspace. An existing
 * conversation's model is read from the conversation itself, which the phone shares.
 *
 * The session lives exactly as long as its sync connection: [start] attaches it, [close]
 * detaches it and releases the retained composer owner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class WebUiChatSession(
    private val generation: MessageGenerationController,
    private val generationStop: GenerationStopAdapter,
    private val clients: ChatClients,
    private val conversations: ConversationRepository,
    private val registry: ConversationStateRegistry,
    executionCoordinator: ConversationExecutionCoordinator,
    settings: SettingsRepository,
    private val transfers: ConversationSettingsTransferCoordinator,
    attachmentProcessor: AttachmentImportProcessor,
    private val scope: CoroutineScope,
) : ChatClient {
    /** Where this session shows; [browserSeq] is the browser's last open request it follows. */
    data class OpenTarget(
        val conversationId: String?,
        val browserSeq: Long,
        /** True when the runtime, not the browser, moved the session (for example New Chat send). */
        val movedByServer: Boolean,
    )

    /** The composer owner the browser shows and that owner's submission phase. */
    data class ComposerState(
        val conversationId: String?,
        val snapshot: ConversationComposerSubmissionSnapshot,
        val seq: Long = 0L,
        val text: String = "",
        val editRevision: Long = 0L,
        val actionId: Long = 0L,
        val modelValid: Boolean = false,
        val generating: Boolean = false,
        val stopping: Boolean = false,
        val modelId: String = "",
        val models: Map<String, String> = emptyMap(),
        val queue: List<QueuedSend> = emptyList(),
    )

    private val target = MutableStateFlow(OpenTarget(null, browserSeq = 0L, movedByServer = false))
    val openTarget: StateFlow<OpenTarget> = target.asStateFlow()
    // Updated together with [target] so a send right after an open captures the new target.
    private val openId = MutableStateFlow<String?>(null)

    private val newChatEntryId = AtomicLong(0L)
    private val _snackbars = MutableSharedFlow<String>(extraBufferCapacity = SNACKBAR_BUFFER)
    val snackbars: SharedFlow<String> = _snackbars.asSharedFlow()
    private val _scrollRequests = MutableSharedFlow<WebSyncEvent.ScrollToBottom>(extraBufferCapacity = 8)
    val scrollRequests: SharedFlow<WebSyncEvent.ScrollToBottom> = _scrollRequests.asSharedFlow()

    // The canonical render owner keeps this client's store equal to what the phone's store holds
    // for the same conversation; Stop snapshots in-flight rows from any showing client's store.
    private val ui = ConversationUiStateAssembler(
        conversations = conversations,
        registry = registry,
        executionCoordinator = executionCoordinator,
        currentConversationId = openId,
        scope = scope,
    )
    private val drafts = ComposerDraftController(
        persistence = SessionDraftPersistence(),
        conversations = conversations,
    )
    private val composers = ConversationComposerController(
        scope = scope,
        drafts = drafts,
        processor = attachmentProcessor,
    )

    /** Model chosen on this browser's New Chat page; null follows the default model. */
    private val newChatModelId = MutableStateFlow<String?>(null)
    private val validModels = settings.validChatModels(scope)
    private val runtimeFacade = CurrentConversationRuntimeFacade(openId, registry, scope)
    /** Etiquetas de modelo ordenadas por provider+nombre; el composer del navegador las lista. */
    private val modelLabels = combine(
        validModels, settings.modelAliases, settings.customProviders, settings.modelProviderNames,
    ) { valid, aliases, providers, showProvider ->
        valid.orEmpty().sortedWith(compareBy(
            { providerDisplayName(ModelId.parse(it).providerName, providers).lowercase() },
            { ModelId.parse(it).apiModelName.lowercase() },
        )).associateWith { modelDisplayName(it, aliases, providers, showProvider[it] != false) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    private val activeModel: StateFlow<Pair<String?, String>?> = openId.flatMapLatest { id ->
        combine(
            if (id == null) newChatModelId else conversations.observeConversation(id).map { it?.modelId },
            settings.selectedModel,
            validModels,
        ) { referenced, fallback, valid ->
            id to if (valid == null) "" else resolveValidModel(referenced, fallback, valid)
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val submission = ConversationComposerSubmissionController(
        scope = scope,
        composers = composers,
        drafts = drafts,
        captureTarget = { ownerId ->
            val current = openId.value
            generation.captureForegroundSendTarget(
                ownerId = ownerId,
                currentId = current,
                isNewChatMode = current == null,
                newChatEntryId = newChatEntryId.get(),
                modelId = activeModel.value?.takeIf { it.first == current }?.second.orEmpty(),
                captureNewChatWorkspace = {
                    NewChatWorkspaceSnapshot(
                        persisted = null,
                        modelId = newChatModelId.value,
                        systemPromptId = null,
                        conversationSettings = null,
                        sessionLocal = true,
                    )
                },
            )
        },
        prepare = { sendTarget, composer ->
            generation.prepareForegroundSend(sendTarget, composer, this@WebUiChatSession)
        },
        send = { admission, text, attachments, onAccepted ->
            generation.sendMessage(admission, text, attachments, onAccepted, this@WebUiChatSession)
        },
        // The in-memory draft store cannot fail to clear, so no retry surface is needed.
    )

    private val ownerMutex = Mutex()
    private val editRevision = MutableStateFlow(0L)
    private val actionId = MutableStateFlow(0L)
    private var retainedOwner: String? = null
    private val ownerState = MutableStateFlow<Pair<String, StateFlow<ConversationComposerSubmissionSnapshot>>?>(null)
    val composerState: Flow<ComposerState> = combine(ownerState, target) { owner, selected ->
        owner?.takeIf { it.first == (selected.conversationId ?: NEW_CHAT_WORKSPACE_ID) }?.let { it to selected }
    }.filterNotNull().flatMapLatest { (owned, _) ->
        val (owner, state) = owned
        val draft = ownerMutex.withLock {
            if (retainedOwner == owner) composers.state(owner) else null
        } ?: return@flatMapLatest flowOf()
        val runtime = owner.takeUnless { it == NEW_CHAT_WORKSPACE_ID }?.let(registry::getOrCreate)
        combine(draft, state, editRevision, actionId, activeModel) { _, _, _, _, _ -> Unit }
            .combine(modelLabels) { _, _ -> Unit }
            .combine(runtime?.queuedSends ?: flowOf(emptyList())) { _, _ -> Unit }
            .combine(runtime?.generating ?: flowOf(false)) { _, _ -> Unit }
            .combine(runtime?.stopping ?: flowOf(false)) { _, _ ->
                ownerMutex.withLock {
                    val selected = target.value
                    if (retainedOwner != owner || owner != (selected.conversationId ?: NEW_CHAT_WORKSPACE_ID)) {
                        null
                    } else {
                        ComposerState(
                            selected.conversationId, state.value, selected.browserSeq, draft.value.text,
                            editRevision.value, actionId.value,
                            activeModel.value?.let { it.first == selected.conversationId && it.second.isNotBlank() } == true,
                            runtime?.generating?.value == true, runtime?.stopping?.value == true,
                            activeModel.value?.takeIf { it.first == selected.conversationId }?.second.orEmpty(),
                            modelLabels.value, runtime?.queuedSends?.value.orEmpty().sortedBy(QueuedSend::createdAt),
                        )
                    }
                }
            }.filterNotNull()
    }

    /** Attaches to the runtime and admits the initial New Chat composer. */
    suspend fun start() {
        clients.attach(this)
        ui.start()
        ownerMutex.withLock { retainOwnerLocked(NEW_CHAT_WORKSPACE_ID) }
    }

    /** The browser opened [conversationId] (null is a fresh New Chat page) with request [seq]. */
    suspend fun open(conversationId: String?, seq: Long) = ownerMutex.withLock {
        if (conversationId == null) {
            newChatEntryId.incrementAndGet()
        }
        editRevision.value = 0L
        actionId.value = 0L
        target.value = OpenTarget(conversationId, seq, movedByServer = false)
        openId.value = conversationId
        retainOwnerLocked(conversationId ?: NEW_CHAT_WORKSPACE_ID)
    }

    /** Applies one ordered browser edit to the canonical composer owner. */
    suspend fun edit(text: String, revision: Long, seq: Long) = ownerMutex.withLock {
        if (seq != target.value.browserSeq || revision <= editRevision.value) return@withLock
        val owner = retainedOwner ?: return@withLock
        composers.updateText(owner, text)
        composers.persistText(owner, text)
        editRevision.value = revision
    }

    suspend fun send(text: String, seq: Long = target.value.browserSeq, commandId: Long = 0L) = ownerMutex.withLock {
        if (seq != target.value.browserSeq) return@withLock
        val owner = retainedOwner ?: return@withLock
        composers.updateText(owner, text)
        submission.submit(owner, text, emptyList())
        actionId.value = commandId
    }

    suspend fun cancelWaiting(seq: Long, commandId: Long) = ownerMutex.withLock {
        if (seq != target.value.browserSeq) return@withLock
        retainedOwner?.let(submission::cancelWaiting)
        actionId.value = commandId
    }

    /** Selección de modelo del navegador: New Chat page o conversación abierta (write durable). */
    suspend fun selectModel(modelId: String, seq: Long, commandId: Long) = ownerMutex.withLock {
        if (seq != target.value.browserSeq) return@withLock
        try {
            if (modelId !in validModels.value.orEmpty()) return@withLock
            val id = openId.value
            if (id == null) newChatModelId.value = modelId
            else if (!conversations.updateConversationModel(id, modelId)) return@withLock
            activeModel.first { resolved ->
                resolved != null && resolved.first == id &&
                    (resolved.second == modelId || modelId !in validModels.value.orEmpty())
            }
        } finally {
            actionId.value = commandId
        }
    }

    suspend fun removeQueued(id: String, seq: Long) = ownerMutex.withLock {
        if (seq == target.value.browserSeq) runtimeFacade.removeQueuedSend(id)
    }

    suspend fun sendQueued(seq: Long, commandId: Long) = ownerMutex.withLock {
        if (seq != target.value.browserSeq) return@withLock
        if (activeModel.value?.let { it.first == openId.value && it.second.isNotBlank() } == true) {
            runtimeFacade.requestQueueDrain()
        }
        actionId.value = commandId
    }

    fun stop(seq: Long = target.value.browserSeq) {
        if (seq == target.value.browserSeq) generationStop.stop(openId.value, this)
    }

    suspend fun close() = withContext(NonCancellable) {
        clients.detach(this@WebUiChatSession)
        ownerMutex.withLock {
            retainedOwner?.let { releaseOwner(it) }
            retainedOwner = null
        }
    }

    /** Moves the composer retain to [ownerId]; the previous owner is released afterwards. */
    private suspend fun retainOwnerLocked(ownerId: String) {
        val previous = retainedOwner
        if (previous == ownerId) return
        composers.loadSelected(ownerId)
        retainedOwner = ownerId
        ownerState.value = ownerId to submission.observeState(ownerId)
        previous?.let { releaseOwner(it) }
    }

    private suspend fun releaseOwner(ownerId: String) {
        submission.releaseState(ownerId)
        composers.releaseSelected(ownerId)
    }

    private suspend fun moveByServer(conversationId: String?) {
        if (conversationId == null) newChatEntryId.incrementAndGet()
        target.value = OpenTarget(conversationId, target.value.browserSeq, movedByServer = true)
        openId.value = conversationId
        retainOwnerLocked(conversationId ?: NEW_CHAT_WORKSPACE_ID)
    }

    // -- ChatClient

    override val openConversationId: String? get() = openId.value
    override val renderStore: ConversationRenderStore get() = ui.renderStore
    override fun isConversationVisible(conversationId: String): Boolean = openId.value == conversationId
    override val branchTransitions = BranchReplacementTransitionCoordinator()

    override suspend fun awaitProjectedPath(conversationId: String, messageId: String) {
        combine(ui.messages, openId) { path, open ->
            open != conversationId || path.any { it.id == messageId }
        }.first { projectedOrClosed -> projectedOrClosed }
    }

    // The browser's message list owns bottom following for its own sends.
    override fun requestScrollToBottomAfter(conversationId: String, messageId: String, attachedOnly: Boolean) {
        val selected = target.value
        if (selected.conversationId == conversationId) {
            _scrollRequests.tryEmit(WebSyncEvent.ScrollToBottom(conversationId, messageId, selected.browserSeq))
        }
    }

    // Send haptics belong to the phone.
    override fun onSendAccepted(conversationId: String, messageId: String) = Unit

    override suspend fun applyCommittedNewConversationState(conversationId: String) {
        transfers.complete(conversationId)
    }

    override suspend fun publishAcceptedNewConversation(
        conversationId: String,
        modelId: String,
        entryId: Long,
    ): Boolean = ownerMutex.withLock {
        if (target.value.conversationId != null || newChatEntryId.get() != entryId) return@withLock false
        val remainingText = composers.state(NEW_CHAT_WORKSPACE_ID).value.text
        composers.load(conversationId)
        try {
            if (remainingText.isNotEmpty()) {
                composers.updateText(conversationId, remainingText)
                composers.persistText(conversationId, remainingText)
                composers.updateText(NEW_CHAT_WORKSPACE_ID, "")
                composers.persistText(NEW_CHAT_WORKSPACE_ID, "")
            }
            moveByServer(conversationId)
        } finally {
            composers.release(conversationId)
        }
        true
    }

    // The browser has no tree-mutation cover; it settles from the synced path.
    override suspend fun beginTreeMutation(conversationId: String, scrollToTarget: Boolean): Long? = null
    override fun settleTreeMutation(requestId: Long?, targetMessageId: String?) = Unit
    override fun failTreeMutation(requestId: Long?) = Unit

    override fun showSnackbar(message: String) {
        _snackbars.tryEmit(message)
    }

    override fun openConversation(conversationId: String) {
        scope.launch { ownerMutex.withLock { moveByServer(conversationId) } }
    }

    // The browser offers no share action.
    override fun showShareText(text: String) = Unit

    override fun settleDeletedConversation(conversationId: String) {
        scope.launch {
            ownerMutex.withLock {
                if (target.value.conversationId == conversationId) moveByServer(null)
            }
        }
    }

    override fun isSubmissionFrozen(conversationId: String): Boolean = submission.isFrozen(conversationId)

    override fun onGenerationActivityChanged(conversationId: String, active: Boolean) =
        if (active) ui.markActive(conversationId) else ui.markIdle(conversationId)

    private companion object {
        const val SNACKBAR_BUFFER = 8
    }
}

/** Browser drafts: kept for the connection only, never written to the phone's Room drafts. */
private class SessionDraftPersistence : ComposerDraftPersistence {
    private val drafts = ConcurrentHashMap<String, ConversationWorkspaceDraft>()

    override suspend fun loadDraft(ownerId: String): ConversationWorkspaceDraft =
        drafts[ownerId] ?: ConversationWorkspaceDraft(text = "", attachmentsJson = null)

    override suspend fun updateDraft(ownerId: String, text: String, attachmentsJson: String?) {
        drafts[ownerId] = ConversationWorkspaceDraft(text, attachmentsJson)
    }

    override suspend fun clearAcceptedDraft(ownerId: String) {
        drafts.remove(ownerId)
    }
}
