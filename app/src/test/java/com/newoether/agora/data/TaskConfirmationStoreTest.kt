package com.newoether.agora.data

import androidx.room.withTransaction
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.TaskConfirmationEntity
import com.newoether.agora.data.local.TaskConfirmationSource
import com.newoether.agora.data.local.TaskConfirmationStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TaskConfirmationStore contract tests (plan PLAN-20261002-TASK-CONFIRM), following the
 * repo's mockk-DAO pattern (EmailDraftStoreTest) — no Robolectric/Room in-memory in this
 * suite. withTransaction is mocked to execute its block, so transactional sequencing is
 * still exercised end-to-end at the store level.
 *
 * Covers: dedup idempotence, cap eviction, single-winner consume, snooze arming and the
 * fetch-and-clear due-gate (the anti-spam invariant: the clear and the select must be the
 * same call), and rows resolved between SELECT and UPDATE being dropped from the batch.
 */
class TaskConfirmationStoreTest {

    private lateinit var chatDao: ChatDao
    private lateinit var database: ChatDatabase
    private lateinit var store: TaskConfirmationStore

    private val pendingRows = MutableStateFlow<List<TaskConfirmationEntity>>(emptyList())

    @Before
    fun setUp() {
        chatDao = mockk(relaxed = true)
        database = mockk(relaxed = true)
        // withTransaction is a suspend extension on RoomDatabase; mocked statically so the
        // store's multi-step writes (dedup + insert + cap eviction; select + conditional
        // clear) run as one unit. Note: for statically-mocked extensions the receiver is
        // argument 0, so the transaction block is the SECOND argument.
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        every { chatDao.observePendingTaskConfirmations() } returns pendingRows
        store = TaskConfirmationStore(chatDao, database)
    }

    private fun draft(
        conversationId: String = "conv",
        modelMessageId: String? = "msg",
        title: String = "Title",
        body: String = "Body",
    ) = TaskConfirmationStore.Draft(
        source = TaskConfirmationSource.HEARTBEAT,
        conversationId = conversationId,
        modelMessageId = modelMessageId,
        title = title,
        bodyText = body,
    )

    private fun row(
        id: String,
        modelMessageId: String? = "msg",
        remindAtEpochMs: Long? = null,
        status: String = TaskConfirmationStatus.PENDING.name,
    ) = TaskConfirmationEntity(
        id = id,
        sourceType = TaskConfirmationSource.HEARTBEAT.name,
        conversationId = "conv",
        modelMessageId = modelMessageId,
        title = "Title",
        bodyText = "Body",
        createdAtEpochMs = 0L,
        remindAtEpochMs = remindAtEpochMs,
        status = status,
    )

    @Test
    fun stageIsIdempotentOnDedupKey() = runTest {
        val existing = row("existing")
        coEvery { chatDao.findTaskConfirmationByDedupKey(any(), any(), any()) } returns existing
        val staged = store.stage(draft())
        assertSame(existing, staged)
        coVerify(exactly = 0) { chatDao.insertTaskConfirmation(any()) }
    }

    @Test
    fun stageInsertsWhenNoDuplicateAndEvictsBeyondCap() = runTest {
        coEvery { chatDao.findTaskConfirmationByDedupKey(any(), any(), any()) } returns null
        val staged = store.stage(draft())
        assertEquals(TaskConfirmationStatus.PENDING.name, staged.status)
        assertNull(staged.remindAtEpochMs)
        coVerify(exactly = 1) { chatDao.insertTaskConfirmation(any()) }
        coVerify(exactly = 1) { chatDao.deleteTaskConfirmationsBeyondCap(TaskConfirmationStore.MAX_CONFIRMATIONS) }
    }

    @Test
    fun stageTrimsTitleAndBody() = runTest {
        coEvery { chatDao.findTaskConfirmationByDedupKey(any(), any(), any()) } returns null
        val staged = store.stage(draft(title = "  T  ", body = "  B  "))
        assertEquals("T", staged.title)
        assertEquals("B", staged.bodyText)
    }

    @Test
    fun stageLosingTheUniqueIndexRaceReturnsTheWinnerAndSkipsEviction() = runTest {
        // INSERT is IGNORE: -1 means another writer owns the dedup key. The caller must get
        // the persisted winner, never the unsaved row (its id would not exist in Room).
        val winner = row("winner")
        coEvery { chatDao.findTaskConfirmationByDedupKey(any(), any(), any()) } returnsMany
            listOf(null, winner)
        coEvery { chatDao.insertTaskConfirmation(any()) } returns -1L
        val staged = store.stage(draft())
        assertSame(winner, staged)
        coVerify(exactly = 0) { chatDao.deleteTaskConfirmationsBeyondCap(any()) }
        coVerify(exactly = 0) { chatDao.cleanupTaskConfirmations(any()) }
    }

    @Test
    fun stageRunsRetentionSoCleanupDoesNotDependOnTheHeartbeat() = runTest {
        // Heartbeat-only cleanup never ran with the heartbeat/daemon off, so task confirmations
        // and their resolved tombstones accumulated forever.
        coEvery { chatDao.findTaskConfirmationByDedupKey(any(), any(), any()) } returns null
        val staged = store.stage(draft())
        coVerify(exactly = 1) {
            chatDao.cleanupTaskConfirmations(
                staged.createdAtEpochMs - TaskConfirmationStore.CLEANUP_AGE_MS,
            )
        }
    }

    @Test
    fun plainTextKeepsMarkdownLinkLabelAndUrl() {
        assertEquals(
            "Pronóstico (https://example.com/clima)",
            TaskConfirmationStore.plainTextForConfirmation("[Pronóstico](https://example.com/clima)"),
        )
        assertEquals(
            "https://example.com",
            TaskConfirmationStore.plainTextForConfirmation("[https://example.com](https://example.com)"),
        )
    }

    @Test
    fun plainTextDropsBlockquoteMarkers() {
        assertEquals(
            "cita uno\ncita dos",
            TaskConfirmationStore.plainTextForConfirmation("> cita uno\n>> cita dos"),
        )
        assertEquals("", TaskConfirmationStore.plainTextForConfirmation(">"))
    }

    @Test
    fun acknowledgeIsSingleWinnerViaPendingPredicate() = runTest {
        coEvery {
            chatDao.transitionTaskConfirmation("r1", TaskConfirmationStatus.PENDING.name, TaskConfirmationStatus.ACKNOWLEDGED.name)
        } returns 1
        assertTrue(store.acknowledge("r1"))

        coEvery {
            chatDao.transitionTaskConfirmation("r2", TaskConfirmationStatus.PENDING.name, TaskConfirmationStatus.ACKNOWLEDGED.name)
        } returns 0
        assertFalse(store.acknowledge("r2"))
    }

    @Test
    fun dismissUsesTransitionToo() = runTest {
        coEvery {
            chatDao.transitionTaskConfirmation(any(), any(), any())
        } returns 1
        assertTrue(store.dismiss("r1"))
        coVerify(exactly = 1) {
            chatDao.transitionTaskConfirmation("r1", TaskConfirmationStatus.PENDING.name, TaskConfirmationStatus.DISMISSED.name)
        }
    }

    @Test
    fun snoozeArmsDeadlineOnlyWhilePending() = runTest {
        coEvery { chatDao.armTaskConfirmationReminder("r1", any()) } returns 1
        assertTrue(store.snooze("r1", 10))

        coEvery { chatDao.armTaskConfirmationReminder("r2", any()) } returns 0
        assertFalse(store.snooze("r2", 10))
    }

    @Test
    fun snoozeRejectsNonPositiveMinutes() = runTest {
        val error = runCatching { store.snooze("r1", 0) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        coVerify(exactly = 0) { chatDao.armTaskConfirmationReminder(any(), any()) }
    }

    @Test
    fun consumeDueRemindersIsFetchAndClear() = runTest {
        val due = listOf(row("a", remindAtEpochMs = 100), row("b", remindAtEpochMs = 200))
        coEvery { chatDao.selectDueTaskConfirmations(300) } returns due
        coEvery { chatDao.clearTaskConfirmationReminderIfStillPending(any()) } returns 1

        val posted = store.consumeDueReminders(300)
        assertEquals(listOf("a", "b"), posted.map { it.id })
        // The clear is what prevents the 60s tick from re-posting the same row forever.
        coVerify(exactly = 1) { chatDao.clearTaskConfirmationReminderIfStillPending("a") }
        coVerify(exactly = 1) { chatDao.clearTaskConfirmationReminderIfStillPending("b") }
    }

    @Test
    fun consumeDueRemindersDropsRowsResolvedBetweenSelectAndUpdate() = runTest {
        coEvery { chatDao.selectDueTaskConfirmations(any()) } returns listOf(row("a", remindAtEpochMs = 100))
        // The banner resolved the row after the SELECT: the clear must observe 0 rows.
        coEvery { chatDao.clearTaskConfirmationReminderIfStillPending("a") } returns 0
        val posted = store.consumeDueReminders(300)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun cleanupOldDelegatesCutoff() = runTest {
        coEvery { chatDao.cleanupTaskConfirmations(any()) } returns 3
        assertEquals(3, store.cleanupOld(cutoffEpochMs = 5L))
        coVerify(exactly = 1) { chatDao.cleanupTaskConfirmations(5L) }
    }
}
