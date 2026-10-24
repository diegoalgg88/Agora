package com.newoether.agora.viewmodel

import android.app.Application
import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.automation.LoopManager
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.tool.AskUserToolProvider
import com.newoether.agora.tool.AutomationToolProvider
import com.newoether.agora.tool.McpToolProvider
import com.newoether.agora.util.SnackbarEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Runtime de chat process-scoped compartido por cada cliente de este proceso (la UI del teléfono
 * y, más adelante, el WebUI). Vive por todo el proceso en el app scope, así que el trabajo de chat
 * que posee sigue corriendo cuando no existe ningún Activity o ViewModel.
 *
 * Posee el core de generación en primer plano, el indexado RAG y los comandos de mensajes (send,
 * regenerate, edit, delete, compact, stop). Los comandos nombran su conversación y su cliente
 * origen explícitamente. El estado por cliente (conversación abierta, scroll, drafts del
 * composer, workspace de chat nuevo) queda con cada cliente.
 */
class ChatRuntime(
    private val application: Application,
    private val appContext: Context,
    conversations: ConversationRepository,
    settings: SettingsRepository,
    memoryManager: MemoryManager,
    skillManager: SkillManager,
    sandboxFactory: SandboxManagerFactory?,
    automationToolProvider: AutomationToolProvider,
    mcpToolProvider: McpToolProvider,
    askUser: AskUserController,
    shellConfirmation: ShellConfirmationController,
    registry: ConversationStateRegistry,
    providerRegistry: ProviderRegistry,
    localProvider: com.newoether.agora.api.local.LocalProvider,
    executionCoordinator: ConversationExecutionCoordinator,
    loopManager: LoopManager,
    taskExecutionEngine: com.newoether.agora.automation.TaskExecutionEngine,
    scope: CoroutineScope,
    /**
     * Providers adicionales del fork (heartbeat, SMS, notifications, assistant device, email);
     * el upstream solo registra automation+mcp+askUser, pero este fork los ofrece en chat.
     */
    extraToolProviders: List<com.newoether.agora.tool.ToolProvider> = emptyList(),
    /** Tool de subagents del chat; el modelo delega en conversaciones hijas aisladas. */
    subagentToolProvider: com.newoether.agora.tool.SubagentToolProvider? = null,
    /** Lifecycle de subagents: apertura de conversación e idle les notifica para entrega durable. */
    subagentManager: com.newoether.agora.automation.SubagentManager? = null,
) {
    /** Subagents: la conversación abierta puede recibir resultados de sus hijos. */
    internal val onConversationOpened: (String) -> Unit =
        if (subagentManager != null) {
            { id -> subagentManager!!.conversationOpened(id) }
        } else {
            {}
        }
    // replay=0: los eventos emitidos sin cliente colectando se descartan en vez de re-reproducirse
    // stale al siguiente cliente. El buffer de 1 slot mantiene tryEmit sin pérdida para
    // colectores lentos.
    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Clientes actualmente adjuntos a este runtime (ver [ChatClient]). */
    internal val clients = ChatClients()

    /** Mensajes runtime-wide que cada cliente conectado puede mostrar (p. ej. prompts de RAG). */
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    /** Subsistema de embeddings: CRUD de modelos + cache RAG + indexado single-message + keys. */
    val ragManager = RagManager(
        conversations = conversations,
        settings = settings,
        appContext = appContext,
        scope = scope,
    ) { _snackbarEvents.emit(it) }

    internal val generationManager: GenerationManager by lazy {
        GenerationManager(
            app = application,
            conversations = conversations,
            memoryManager = memoryManager,
            skillManager = skillManager,
            context = appContext,
            sandboxFactory = sandboxFactory,
            additionalToolProviders = listOf(
                automationToolProvider,
                mcpToolProvider,
                AskUserToolProvider(askUser),
            ) + extraToolProviders + listOfNotNull(subagentToolProvider),
            customProviders = { settings.customProviders.value },
        ).also { gm ->
            // Gate vive en RagManager.indexMessageForRag (autoCacheEnabled + modelo activo).
            gm.onMessagePersisted = { messageId, text -> ragManager.indexMessageForRag(messageId, text) }
            gm.onConfirmShellCommand = shellConfirmation::confirm
        }
    }

    /** Ensamblado stateless de requests compartido por cada comando; los previews de contexto también lo leen. */
    internal val requestBuilder = GenerationRequestBuilder(
        settings = settings,
        convRepo = conversations,
        memoryManager = memoryManager,
        skillManager = skillManager,
        providerRegistry = providerRegistry,
        ragManager = ragManager,
        appContext = appContext,
    )

    internal val messageGeneration: MessageGenerationController by lazy {
        MessageGenerationController(
            scope = scope,
            application = application,
            appContext = appContext,
            convRepo = conversations,
            settings = settings,
            registry = registry,
            generationManagerProvider = { generationManager },
            requestBuilder = requestBuilder,
            payloadBuilder = MessagePayloadBuilder(),
            providerRegistry = providerRegistry,
            localProvider = localProvider,
            executionCoordinator = executionCoordinator,
            clients = clients,
            onSnackbar = { message -> _snackbarEvents.tryEmit(SnackbarEvent(message)) },
            onUserMessagePersisted = ragManager::indexMessageForRag,
            pauseConversationTasks = { conversationId -> loopManager.stopLoop(conversationId) },
        )
    }

    internal val generationStop: GenerationStopAdapter by lazy {
        GenerationStopAdapter(
            registry = registry,
            clients = clients,
            finalizer = GenerationFinalizer(conversations, ragManager::indexMessageForRag),
            failureText = { appContext.getString(R.string.failed_to_generate) },
        )
    }

    internal val conversationLifecycle = ConversationLifecycleController(
        conversations = conversations,
        scope = scope,
        clients = clients,
        stopLoop = { conversationId -> loopManager.stopLoop(conversationId) },
        tryWithConversationLock = { conversationId, block ->
            executionCoordinator.tryWithConversationLock(conversationId) { block() }
        },
        removeRuntime = registry::remove,
        stopGeneration = { conversationId, origin -> generationStop.stop(conversationId, origin) },
        deletedElsewhereText = { appContext.getString(R.string.conversation_deleted_elsewhere) },
    )

    internal val conversationForkShare = ConversationForkShareController(
        service = ConversationForkShareService(
            conversations,
            settings,
            java.io.File(application.filesDir, "fork-attachments"),
        ),
        scope = scope,
        forkFailureText = { reason -> appContext.getString(R.string.conversation_fork_failed, reason) },
        shareFailureText = { reason -> appContext.getString(R.string.conversation_share_failed, reason) },
    )

    // Loop cycles for a conversation some client shows use the regular Send path; the bridge
    // waits for that exact durable turn and returns a typed result to the automation lease owner.
    private val foregroundAutomationBridge = ForegroundAutomationBridgeController(
        isConversationOpen = clients::isConversationOpen,
        send = { conversationId, userText, modelId, requestKind ->
            messageGeneration.sendMessageFromAutomationAwaitingCompletion(
                conversationId,
                userText,
                modelId,
                requestKind,
            )
        },
        loadMessage = conversations::getMessage,
        attach = taskExecutionEngine::attachForegroundSendBridge,
        detach = taskExecutionEngine::detachForegroundSendBridge,
    )

    init {
        // The runtime is the registry's single, process-lifetime callback owner.
        registry.attachUiCallbacks(this) { state ->
            state.onActive = { conversationId ->
                // Published synchronously with the slot claim so Stop and edit closure are immediate.
                clients.generationActivityChanged(conversationId, active = true)
            }
            state.onIdle = { conversationId ->
                clients.generationActivityChanged(conversationId, active = false)
                // Un subagent idle notifica al manager para orquestar la entrega durable.
                subagentManager?.conversationIdle(conversationId)
            }
            state.onStreamCommit = clients::commitTerminalStreamingMessage
            state.onQueueDrainRequested = { settledState ->
                settledState.scope.launch {
                    messageGeneration.drainQueuedAfterGeneration(settledState)
                }
            }
        }
        foregroundAutomationBridge.start()
    }
}
