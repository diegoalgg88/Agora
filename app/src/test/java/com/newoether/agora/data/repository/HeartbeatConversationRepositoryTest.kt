package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartbeatConversationRepositoryTest {
    private fun repository(dao: ChatDao) = ConversationRepository(dao, database = null)

    @Test
    fun reusesExistingHeartbeatOriginConversation() = runTest {
        val dao = mockk<ChatDao>(relaxed = true)
        val existing = ChatEntity(id = "hb-1", title = "Heartbeat", origin = "heartbeat")
        coEvery { dao.getConversationByOrigin("heartbeat") } returns existing

        val id = repository(dao).getOrCreateHeartbeatConversationId()

        assertEquals("hb-1", id)
        coVerify(exactly = 0) { dao.upsertConversation(any()) }
    }

    @Test
    fun adoptsLegacyHeartbeatTitledConversationAndMarksOrigin() = runTest {
        val dao = mockk<ChatDao>(relaxed = true)
        val legacy = ChatEntity(id = "legacy-1", title = "Heartbeat", origin = "user")
        coEvery { dao.getConversationByOrigin("heartbeat") } returns null
        coEvery { dao.getConversationByTitle("Heartbeat") } returns legacy

        val id = repository(dao).getOrCreateHeartbeatConversationId()

        assertEquals("legacy-1", id)
        coVerify(exactly = 1) {
            dao.upsertConversation(match { it.id == "legacy-1" && it.origin == "heartbeat" })
        }
    }

    @Test
    fun createsNewHeartbeatConversationOnFirstUse() = runTest {
        val dao = mockk<ChatDao>(relaxed = true)
        coEvery { dao.getConversationByOrigin("heartbeat") } returns null
        coEvery { dao.getConversationByTitle("Heartbeat") } returns null

        repository(dao).getOrCreateHeartbeatConversationId(modelId = "model-x")

        coVerify(exactly = 1) {
            dao.upsertConversation(
                match {
                    it.title == "Heartbeat" &&
                        it.origin == "heartbeat" &&
                        it.modelId == "model-x"
                }
            )
        }
    }
}