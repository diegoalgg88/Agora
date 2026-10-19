package com.newoether.agora.viewmodel

import android.app.Application
import android.content.Context
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

/**
 * Runtime de chat process-scoped compartido por cada cliente de este proceso (la UI del teléfono
 * y, más adelante, el WebUI). Vive por todo el proceso en el app scope, así que el trabajo de chat
 * que posee sigue corriendo cuando no existe ningún Activity o ViewModel.
 *
 * Posee el core de generación en primer plano y el indexado RAG. El estado por cliente
 * (conversación abierta, scroll, drafts del composer, workspace de chat nuevo) queda con cada
 * cliente.
 */
class ChatRuntime(
    application: Application,
    appContext: Context,
    conversations: ConversationRepository,
    settings: SettingsRepository,
    memoryManager: MemoryManager,
    skillManager: SkillManager,
    sandboxFactory: SandboxManagerFactory?,
    automationToolProvider: AutomationToolProvider,
    mcpToolProvider: McpToolProvider,
    askUser: AskUserController,
    shellConfirmation: ShellConfirmationController,
    scope: CoroutineScope,
    /**
     * Providers adicionales del fork (heartbeat, SMS, notifications, assistant device, email);
     * el upstream solo registra automation+mcp+askUser, pero este fork los ofrece en chat.
     */
    extraToolProviders: List<com.newoether.agora.tool.ToolProvider> = emptyList(),
) {
    // replay=0: los eventos emitidos sin cliente colectando se descartan en vez de re-reproducirse
    // stale al siguiente cliente. El buffer de 1 slot mantiene tryEmit sin pérdida para
    // colectores lentos.
    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Mensajes runtime-wide que cada cliente conectado puede mostrar (p. ej. prompts de RAG). */
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    /** Clientes actualmente adjuntos a este runtime (ver [ChatClient]). */
    internal val clients = ChatClients()

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
            ) + extraToolProviders,
            customProviders = { settings.customProviders.value },
        ).also { gm ->
            // Gate vive en RagManager.indexMessageForRag (autoCacheEnabled + modelo activo).
            gm.onMessagePersisted = { messageId, text -> ragManager.indexMessageForRag(messageId, text) }
            gm.onConfirmShellCommand = shellConfirmation::confirm
        }
    }
}
