package com.newoether.agora.service

import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppForegroundTracker {
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()
    private val _chatPresented = MutableStateFlow(false)
    val chatPresented: StateFlow<Boolean> = _chatPresented.asStateFlow()

    private val _presentedConversation = MutableStateFlow<String?>(null)

    /** Conversación mostrada por la pantalla de chat, o null en un chat nuevo o sin ninguna. */
    val presentedConversation: StateFlow<String?> = _presentedConversation.asStateFlow()

    fun setPresentedConversation(conversationId: String?) {
        _presentedConversation.value = conversationId
    }

    /**
     * Si un request de [conversationId] está en pantalla: la app al frente con el chat mostrando
     * ESA conversación. Un request sin conversación puede responderse desde cualquier chat.
     */
    fun isShownInChat(
        conversationId: String?,
        foreground: Boolean = _foreground.value,
        chatPresented: Boolean = _chatPresented.value,
        presented: String? = _presentedConversation.value,
    ): Boolean = foreground && chatPresented && (conversationId == null || conversationId == presented)

    @Volatile
    var isInForeground: Boolean = false
        private set

    @Volatile
    var isChatPresented: Boolean = false
        private set

    fun setInForeground(inForeground: Boolean) {
        if (isInForeground == inForeground) return
        isInForeground = inForeground
        _foreground.value = inForeground
        listeners.forEach { it(inForeground) }
    }

    fun setChatPresented(presented: Boolean) {
        if (isChatPresented == presented) return
        isChatPresented = presented
        _chatPresented.value = presented
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
        listener(isInForeground)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }
}
