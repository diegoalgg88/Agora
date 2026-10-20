package com.newoether.agora.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.updateConversationModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConversationModelWriteTest {
    @Test
    fun modelWriteKeepsEveryOtherFieldAndNeverRecreatesADeletedOwner() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = database.chatDao()
            val repository = ConversationRepository(dao, database)
            val before = ChatEntity(
                id = "conversation", title = "latest title", modelId = "old:model",
                draftText = "later draft", draftAttachments = "[]", selectedBranchesJson = "{}",
                lastUpdated = 42L, dataChangedAt = Long.MAX_VALUE - 100,
            )
            dao.upsertConversation(before)
            assertTrue(repository.updateConversationModel(before.id, "new:model"))
            val after = requireNotNull(dao.getConversation(before.id))
            assertEquals(before.copy(modelId = "new:model", dataChangedAt = before.dataChangedAt + 1), after)
            assertFalse(repository.updateConversationModel("deleted", "new:model"))
            assertEquals(null, dao.getConversation("deleted"))
        } finally {
            database.close()
        }
    }
}
