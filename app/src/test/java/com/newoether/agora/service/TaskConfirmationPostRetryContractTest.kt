package com.newoether.agora.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 contract (Universal Installer's InstallPromptNotifier rule, adapted headless):
 * a notification post refused because notifications are blocked must never strand the
 * result silently. `post`/`postInfo` return Boolean, the refused post is logged, and the
 * PROMPT surfacing owner arms a deferred re-post (`schedulePostRetry` → receiver
 * ACTION_RETRY_POST) so the prompt surfaces once the user unblocks. The retry must not
 * touch the snooze field (`remindAtEpochMs`) — the banner row stays visible while blocked.
 */
class TaskConfirmationPostRetryContractTest {

    @Test
    fun `post and postInfo return Boolean and log refusals`() {
        val notifier = sourceFile(
            "app/src/main/java/com/newoether/agora/service/TaskPromptNotifier.kt",
        )
        assertTrue(notifier.contains("fun post(\n        confirmationId: String,\n        sourceType: String,\n        rowTitle: String,\n        body: String,\n        conversationId: String,\n    ): Boolean {"))
        assertTrue(notifier.contains("fun postInfo(\n        sourceType: String,\n        title: String,\n        body: String,\n        conversationId: String,\n        modelMessageId: String?,\n    ): Boolean {"))
        // Both refused paths log a structured line before returning false.
        assertTrue(notifier.contains("post refused (notifications blocked)"))
        assertTrue(notifier.contains("info post refused (notifications blocked)"))
    }

    @Test
    fun `schedulePostRetry uses a distinct alarm action and never the snooze field`() {
        val notifier = sourceFile(
            "app/src/main/java/com/newoether/agora/service/TaskPromptNotifier.kt",
        )
        val receiver = sourceFile(
            "app/src/main/java/com/newoether/agora/automation/TaskConfirmationReceiver.kt",
        )
        assertTrue(notifier.contains("fun schedulePostRetry(confirmationId: String)"))
        // Distinct action + distinct data URI => its own PendingIntent, never colliding
        // with the snooze reminder's.
        assertTrue(notifier.contains("TaskConfirmationReceiver.ACTION_RETRY_POST"))
        assertTrue(notifier.contains("\"agora://task-confirmation/retry/\$confirmationId\""))
        assertFalse(notifier.substringAfter("fun schedulePostRetry").substringBefore("suspend fun postDueReminders").contains("remindAtEpochMs"))
        // The retry action exists on the receiver and re-arms while still blocked.
        assertTrue(receiver.contains("ACTION_RETRY_POST"))
        assertTrue(receiver.contains("schedulePostRetry(row.id)"))
        // The retry only posts a row that is still PENDING.
        assertTrue(receiver.contains("if (!stillPending) return@launch"))
    }

    @Test
    fun `surfacing arms the retry when the prompt post is refused`() {
        val surfacing = sourceFile(
            "app/src/main/java/com/newoether/agora/automation/TaskConfirmationSurfacing.kt",
        )
        assertTrue(surfacing.contains("val posted = notifier.post("))
        assertTrue(surfacing.contains("if (!posted) {\n            notifier.schedulePostRetry(staged.id)\n        }"))
    }

    @Test
    fun `boot re-arms retry-post alarms via the complementary no-snooze query`() {
        val boot = sourceFile("app/src/main/java/com/newoether/agora/service/BootReceiver.kt")
        val store = sourceFile("app/src/main/java/com/newoether/agora/data/TaskConfirmationStore.kt")
        val dao = sourceFile(
            "app/src/main/java/com/newoether/agora/data/local/ChatHeartbeatSmsDao.kt",
        )
        // The store exposes the complementary query the snooze query cannot cover.
        assertTrue(store.contains("suspend fun pendingWithoutSnooze()"))
        assertTrue(dao.contains("suspend fun selectPendingWithoutSnooze()"))
        assertTrue(dao.contains("remindAtEpochMs IS NULL"))
        // BootReceiver re-arms retry-post from it, alongside the snooze re-arm.
        assertTrue(boot.contains("pendingWithoutSnooze().forEach { row ->"))
        assertTrue(boot.contains("schedulePostRetry(row.id)"))
        // Snooze re-arm stays intact (both paths, not either/or).
        assertTrue(boot.contains("armedReminders().forEach { row ->"))
        assertTrue(boot.contains("scheduleReminder(row.id, it)"))
    }

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
