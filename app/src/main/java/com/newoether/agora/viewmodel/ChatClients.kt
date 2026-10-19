package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Un cliente de chat adjunto al [ChatRuntime]: hoy la UI del teléfono, más adelante cada sesión
 * del WebUI.
 *
 * Un cliente posee lo que muestra. El runtime jamás selecciona una conversación por un cliente;
 * solo reparte los cambios con scope de conversación a los clientes que muestran esa conversación
 * en ese momento.
 */
internal interface ChatClient {
    /** Conversación que este cliente muestra ahora, o null cuando no muestra ninguna (p. ej. New Chat). */
    val openConversationId: String?

    /** Render snapshot de [openConversationId]. El runtime solo escribe en él mientras esa conversación está abierta. */
    val renderStore: ConversationRenderStore

    /** True cuando el usuario puede ver [conversationId] en este cliente. */
    fun isConversationVisible(conversationId: String): Boolean
}

/** Fences de proyección de Room abiertas en cada render store de cliente para un input aceptado. */
internal class ChatClientRoomFences internal constructor(
    internal val byStore: Map<ConversationRenderStore, RoomMessageProjectionFence>,
)

/**
 * Los clientes adjuntos al runtime.
 *
 * Una conversación cuenta como abierta (o visible) cuando cualquier cliente adjunto la tiene
 * abierta (o visible). Los cambios de grafo con scope de conversación van a cada cliente que
 * tiene esa conversación abierta en el momento de la escritura.
 */
internal class ChatClients {
    private val attached = CopyOnWriteArrayList<ChatClient>()

    fun attach(client: ChatClient) {
        attached.addIfAbsent(client)
    }

    fun detach(client: ChatClient) {
        attached.remove(client)
    }

    fun isConversationOpen(conversationId: String): Boolean =
        attached.any { it.openConversationId == conversationId }

    fun isConversationVisible(conversationId: String): Boolean =
        attached.any { it.isConversationVisible(conversationId) }

    fun commitGraph(
        conversationId: String,
        committedMessages: List<ChatMessage>,
        selectedChildren: Map<String?, String>,
        streamingMessage: ChatMessage?,
        fences: ChatClientRoomFences? = null,
    ) {
        val stores = storesShowing(conversationId)
        stores.forEach { store ->
            store.commitGraph(
                committedMessages = committedMessages,
                selectedChildren = selectedChildren,
                streamingMessage = streamingMessage,
                roomProjectionFence = fences?.byStore?.get(store),
            )
        }
        // Un cliente que dejó la conversación tras abrirse su fence debe igualmente liberarlo.
        fences?.byStore?.forEach { (store, fence) ->
            if (store !in stores) store.releaseRoomMessageProjectionFence(fence)
        }
    }

    fun replaceGraph(
        conversationId: String,
        allMessages: List<ChatMessage>,
        selectedChildren: Map<String?, String>,
    ) {
        storesShowing(conversationId).forEach { store ->
            store.replaceGraph(allMessages = allMessages, selectedChildren = selectedChildren)
        }
    }

    /** Abre un fence en cada cliente que muestra [conversationId]; null cuando ningún cliente la muestra. */
    fun beginRoomProjectionFences(conversationId: String): ChatClientRoomFences? =
        storesShowing(conversationId)
            .associateWith { it.beginRoomMessageProjectionFence() }
            .takeIf { it.isNotEmpty() }
            ?.let(::ChatClientRoomFences)

    fun releaseRoomProjectionFences(fences: ChatClientRoomFences) {
        fences.byStore.forEach { (store, fence) -> store.releaseRoomMessageProjectionFence(fence) }
    }

    private fun storesShowing(conversationId: String): List<ConversationRenderStore> =
        attached.filter { it.openConversationId == conversationId }.map { it.renderStore }
}
