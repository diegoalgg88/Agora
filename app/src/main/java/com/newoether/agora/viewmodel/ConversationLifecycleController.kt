package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordina los cambios durables de metadata de conversaciones y la limpieza tras borrado para
 * cada cliente.
 *
 * Un borrado lo emite un [ChatClient] (su origen). El origen cubre su propia página mientras
 * muestra la conversación; cada cliente que aún muestra la conversación cuando se borra cae a
 * New Chat, y los clientes distintos del origen reciben antes el porqué.
 */
internal class ConversationLifecycleController(
    private val conversations: ConversationRepository,
    private val scope: CoroutineScope,
    private val clients: ChatClients,
    private val stopLoop: suspend (String) -> Unit,
    private val tryWithConversationLock: suspend (String, suspend () -> Unit) -> Boolean,
    private val removeRuntime: (String) -> Unit,
    private val stopGeneration: (conversationId: String, origin: ChatClient) -> Unit,
    private val deletedElsewhereText: () -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    fun rename(conversationId: String, newTitle: String) {
        scope.launch {
            conversations.updateConversationTitle(conversationId, newTitle)
        }
    }

    fun delete(
        origin: ChatClient,
        conversationId: String,
        expectedMessageIds: Set<String>? = null,
        onResult: (Boolean) -> Unit = {},
    ): Boolean {
        if (clients.isSubmissionFrozen(conversationId)) return false
        scope.launch(ioDispatcher) {
            var deleted = false
            var viewersAtCommit = emptyList<ChatClient>()
            val selectedAtDispatch = origin.openConversationId == conversationId
            var transitionRequestId: Long? = null
            try {
                if (selectedAtDispatch) {
                    // Esto suspende por el fade del overlay, así que ningún trabajo destructivo de
                    // storage puede empezar antes de que la superficie de carga de la página
                    // seleccionada sea visible.
                    transitionRequestId = origin.beginTreeMutation(
                        conversationId = conversationId,
                        scrollToTarget = false,
                    )
                }
                tryWithConversationLock(conversationId) {
                    // La admisión de Send usa este mismo lock. Solo el ganador puede parar trabajo vivo.
                    if (clients.isSubmissionFrozen(conversationId)) return@tryWithConversationLock
                    if (
                        expectedMessageIds != null &&
                        conversations.getMessageTopologySnapshot(conversationId)
                            .mapTo(linkedSetOf()) { it.id } != expectedMessageIds
                    ) return@tryWithConversationLock
                    viewersAtCommit = clients.showing(conversationId)
                    if (viewersAtCommit.isNotEmpty()) stopGeneration(conversationId, origin)
                    stopLoop(conversationId)
                    conversations.deleteConversation(conversationId)
                    deleted = true
                }
                if (deleted) {
                    removeRuntime(conversationId)
                    if (viewersAtCommit.isNotEmpty()) {
                        withContext(mainDispatcher) {
                            viewersAtCommit.forEach { viewer ->
                                if (viewer !== origin) viewer.showSnackbar(deletedElsewhereText())
                                // El origen entrega su overlay ya visible a New Chat.
                                viewer.settleDeletedConversation(conversationId)
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                runCatching {
                    DebugLog.e(
                        "ConversationLifecycle",
                        "Failed to delete conversation $conversationId",
                        error,
                    )
                }
            } finally {
                withContext(NonCancellable + mainDispatcher) {
                    if (selectedAtDispatch && (!deleted || origin !in viewersAtCommit)) {
                        origin.failTreeMutation(transitionRequestId)
                    }
                    onResult(deleted)
                }
            }
        }
        return true
    }
}
