package com.newoether.agora.viewmodel

import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Publica un Send aceptado sin convertir el fallo de un callback de presentación en fallo del
 * Send. El evento va al cliente origen del Send, o a cada cliente que muestra la conversación
 * cuando el Send no tiene origen (queue drain, ciclo de Loop).
 */
internal class SendAcceptanceNotifier(
    private val clients: ChatClients,
) {
    suspend fun notify(
        acceptance: SendAcceptance,
        onAccepted: suspend (SendAcceptance) -> Unit,
        origin: ChatClient?,
        publishEvent: Boolean = true,
    ) {
        // Draft settlement is authoritative: callers must observe a failure instead of mistaking a
        // presentation callback catch for a successful acknowledgement.
        withContext(NonCancellable) { onAccepted(acceptance) }
        if (publishEvent) publish(acceptance, origin)
    }

    fun publish(acceptance: SendAcceptance, origin: ChatClient?) {
        clients.effectTargets(acceptance.conversationId, origin).forEach { client ->
            try {
                client.onSendAccepted(acceptance.conversationId, acceptance.messageId)
            } catch (error: Exception) {
                DebugLog.e(
                    "SendAcceptanceNotifier",
                    "Failed to publish accepted Send ${acceptance.messageId}",
                    error,
                )
            }
        }
    }
}
