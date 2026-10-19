package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.util.DebugLog

/**
 * Adapta el intent de Stop de un cliente para una conversación al runtime de esa conversación y
 * su efecto durable autorizado de finalización. No posee estado de Run, Job, scope, barrier, ni
 * identidad de efecto. Las correcciones de render van a cada cliente que muestra la
 * conversación; un fallo de persistencia se reporta al cliente que pidió el stop.
 */
internal class GenerationStopAdapter(
    private val registry: ConversationStateRegistry,
    private val clients: ChatClients,
    private val finalizer: GenerationFinalizer,
    private val failureText: () -> String,
) {
    fun stop(conversationId: String?, origin: ChatClient) {
        conversationId ?: return
        val state = registry.get(conversationId) ?: return
        state.requestStop { result ->
            val stoppedMessage = result.stoppedMessage
            val messages = stoppedMessage?.let(::listOf)
                ?: snapshotStoppedRows(clients.renderStoresShowing(result.conversationId))
            val effect = result.finalizationEffect ?: return@requestStop
            // The finalizer receives exactly the reducer-emitted identity and returns an identified
            // persistence result to this same runtime host. It cannot release the slot itself.
            finalizer.launchStopFinalization(
                scope = state.scope,
                identity = effect.identity,
                messages = messages,
                onFinalized = { completion ->
                    val outcome = state.finishStopFinalization(completion)
                    // Delayed/duplicate/stale completions cannot change runtime or presentation.
                    if (!outcome.accepted) return@launchStopFinalization
                    if (completion.success) {
                        // Room invalidation and the runtime projection settle asynchronously. Keep
                        // the exact terminal overlay visible until the durable result is accepted.
                        if (stoppedMessage != null) {
                            clients.renderStoresShowing(result.conversationId).forEach { store ->
                                store.commitTerminalStreamingMessage(stoppedMessage)
                            }
                        }
                        state.clearStoppedOverlay()
                    } else {
                        origin.showSnackbar(failureText())
                    }
                },
            )
        }
    }

    /**
     * Sin overlay de streaming, las rows detenidas son las in-flight que renderizan los clientes.
     * Se leen del primer store que muestra la conversación (todos muestran el mismo grafo) y se
     * marcan STOPPED en cada store; ningún store mostrándola significa que no hay qué finalizar.
     */
    private fun snapshotStoppedRows(stores: List<ConversationRenderStore>): List<ChatMessage> = runCatching {
        val source = stores.firstOrNull() ?: return@runCatching emptyList()
        source.allMessages.mapNotNull { message ->
            if (
                (message.participant == Participant.MODEL || message.isContextCompact()) &&
                message.status.isInFlight()
            ) {
                message.copy(status = MessageStatus.STOPPED)
            } else {
                null
            }
        }.also { stopped ->
            val byId = stopped.associateBy(ChatMessage::id)
            if (byId.isNotEmpty()) {
                stores.forEach { store ->
                    store.updateAllMessages { rows -> rows.map { byId[it.id] ?: it } }
                }
            }
        }
    }.getOrElse { error ->
        DebugLog.e("GenerationStopAdapter", "Failed to snapshot stopped render rows", error)
        emptyList()
    }
}

private fun MessageStatus.isInFlight(): Boolean =
    this == MessageStatus.SENDING ||
        this == MessageStatus.THINKING ||
        this == MessageStatus.TOOL_CALLING ||
        this == MessageStatus.TRANSCRIBING
