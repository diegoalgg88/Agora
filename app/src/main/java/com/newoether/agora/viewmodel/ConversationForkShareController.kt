package com.newoether.agora.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Adapta los intents de fork/share del cliente que muestra una conversación a los outcomes
 * tipados del service. Cada outcome (la conversación forkeada a abrir, el texto de share, el
 * fallo) va solo a ese cliente origen.
 *
 * Los orígenes asíncronos (el navegador) pasan [isCurrent] para que un resultado tardío no
 * llegue a una conversación que ya cambiaron, y [onResult] para sostener su propia
 * confirmación hasta que el teléfono responde.
 */
internal class ConversationForkShareController(
    private val service: ConversationForkShareService,
    private val scope: CoroutineScope,
    private val forkFailureText: (String) -> String,
    private val shareFailureText: (String) -> String,
) {
    fun fork(
        origin: ChatClient,
        messageId: String? = null,
        isCurrent: () -> Boolean = { true },
        openFork: suspend (String) -> Boolean = { origin.openConversation(it); true },
        onResult: (Boolean) -> Unit = {},
    ): Boolean {
        val conversationId = origin.openConversationId ?: return false
        scope.launch {
            var forked = false
            try {
                when (val result = service.fork(conversationId, messageId)) {
                    is ConversationForkShareService.ForkResult.Success -> {
                        if (isCurrent()) forked = openFork(result.conversationId)
                    }
                    is ConversationForkShareService.ForkResult.Failure ->
                        if (isCurrent()) origin.showSnackbar(forkFailureText(result.reason))
                }
            } finally {
                onResult(forked)
            }
        }
        return true
    }

    fun shareConversation(
        origin: ChatClient,
        isCurrent: () -> Boolean = { true },
        onResult: (String?) -> Unit = { text -> text?.let(origin::showShareText) },
    ): Boolean = share(origin, isCurrent, onResult) { conversationId -> service.shareAll(conversationId) }

    /**
     * Shares one generation. [onResult] receives the share text, or null when nothing was shared,
     * so an origin that shows its own confirmation can hold it until the phone answers.
     */
    fun shareGeneration(
        origin: ChatClient,
        assistantMessageId: String,
        isCurrent: () -> Boolean = { true },
        onResult: (String?) -> Unit = { text -> text?.let(origin::showShareText) },
    ): Boolean = share(origin, isCurrent, onResult) { conversationId ->
        service.shareRun(conversationId, assistantMessageId)
    }

    fun shareMessages(origin: ChatClient, messageIds: Set<String>) {
        if (messageIds.isEmpty()) return
        share(origin, load = { conversationId -> service.shareMessages(conversationId, messageIds) })
    }

    private fun share(
        origin: ChatClient,
        isCurrent: () -> Boolean = { true },
        onResult: (String?) -> Unit = { text -> text?.let(origin::showShareText) },
        load: suspend (conversationId: String) -> ConversationForkShareService.ShareResult,
    ): Boolean {
        val conversationId = origin.openConversationId ?: return false
        scope.launch {
            var text: String? = null
            try {
                when (val result = load(conversationId)) {
                    is ConversationForkShareService.ShareResult.Success ->
                        if (isCurrent()) text = result.text
                    is ConversationForkShareService.ShareResult.Failure ->
                        if (isCurrent()) origin.showSnackbar(shareFailureText(result.reason))
                }
            } finally {
                onResult(text)
            }
        }
        return true
    }
}
