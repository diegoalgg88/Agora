package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatClientsTest {
    private class FakeClient(var open: String?, var visible: Boolean = false) : ChatClient {
        override val openConversationId: String? get() = open
        override val renderStore = ConversationRenderStore()
        override fun isConversationVisible(conversationId: String) = visible && open == conversationId
    }

    private fun message(id: String) = ChatMessage(id = id, text = id, participant = Participant.USER)

    @Test
    fun `open and visible mean any attached client`() {
        val clients = ChatClients()
        val phone = FakeClient(open = "a", visible = false)
        val web = FakeClient(open = "b", visible = true)
        clients.attach(phone)
        clients.attach(web)

        assertTrue(clients.isConversationOpen("a"))
        assertTrue(clients.isConversationOpen("b"))
        assertFalse(clients.isConversationOpen("c"))
        assertFalse(clients.isConversationVisible("a"))
        assertTrue(clients.isConversationVisible("b"))

        clients.detach(web)
        assertFalse(clients.isConversationOpen("b"))
        assertFalse(clients.isConversationVisible("b"))
    }

    @Test
    fun `graph commits reach only clients showing that conversation`() {
        val clients = ChatClients()
        val first = FakeClient(open = "a")
        val second = FakeClient(open = "a")
        val other = FakeClient(open = "b")
        listOf(first, second, other).forEach(clients::attach)

        clients.commitGraph(
            conversationId = "a",
            committedMessages = listOf(message("m1")),
            selectedChildren = mapOf(null to "m1"),
            streamingMessage = null,
        )

        assertEquals(listOf("m1"), first.renderStore.allMessages.map { it.id })
        assertEquals(listOf("m1"), second.renderStore.allMessages.map { it.id })
        assertTrue(other.renderStore.allMessages.isEmpty())

        clients.replaceGraph("b", listOf(message("m2")), emptyMap())
        assertEquals(listOf("m2"), other.renderStore.allMessages.map { it.id })
        assertEquals(listOf("m1"), first.renderStore.allMessages.map { it.id })
    }

    @Test
    fun `fences open per showing client and a client that left is still released`() {
        val clients = ChatClients()
        val stays = FakeClient(open = "a")
        val leaves = FakeClient(open = "a")
        clients.attach(stays)
        clients.attach(leaves)

        assertNull(clients.beginRoomProjectionFences("none"))
        val fences = requireNotNull(clients.beginRoomProjectionFences("a"))
        assertEquals(2, fences.byStore.size)

        leaves.open = "b"
        clients.commitGraph(
            conversationId = "a",
            committedMessages = listOf(message("m1")),
            selectedChildren = emptyMap(),
            streamingMessage = null,
            fences = fences,
        )

        assertEquals(listOf("m1"), stays.renderStore.allMessages.map { it.id })
        assertTrue(leaves.renderStore.allMessages.isEmpty())
        // Released fence: a later Room projection on the store that left is no longer deferred.
        leaves.renderStore.setAllMessages(listOf(message("m3")))
        assertEquals(listOf("m3"), leaves.renderStore.allMessages.map { it.id })
    }
}
