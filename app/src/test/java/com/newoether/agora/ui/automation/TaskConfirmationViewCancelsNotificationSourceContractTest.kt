package com.newoether.agora.ui.automation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PLAN-20261010-CONFIRMATION-ACK F1 contract: opening the confirmation card must cancel the
 * shade notification — the entry has done its job once the live card is in front of the user
 * (observed: card open + shade notification both alive for the same row, because the
 * notification posts with setAutoCancel(false) and nothing else removed it). The cancel lives
 * in `TaskConfirmationActivity.load` — the single landing point of BOTH entry paths (the
 * chat banner's "Ver" and the notification's contentIntent) — and must fire only AFTER the
 * row is confirmed still PENDING (viewing is not resolving: the durable row and the banner
 * keep offering Confirm/Dismiss/Snooze).
 */
class TaskConfirmationViewCancelsNotificationSourceContractTest {

    @Test
    fun `opening the card cancels the shade notification after the pending check`() {
        val activity = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/automation/TaskConfirmationActivity.kt",
        )

        // load() cancels the notification once the row is confirmed PENDING.
        assertTrue(activity.contains("taskPromptNotifier.cancel(id)"))
        // The cancel is ordered after the still-PENDING gate in load() — a resolved row
        // (stale intent) finishes instead of cancelling an unrelated re-post.
        val pendingGate = activity.indexOf(
            "current.status != TaskConfirmationStatus.PENDING.name",
        )
        val cancel = activity.indexOf("taskPromptNotifier.cancel(id)")
        assertTrue(pendingGate in 0 until cancel)
        // The resolve path keeps its own unconditional cancel (single-winner result may
        // be false when another surface consumed the row — the notification must not linger).
        assertTrue(activity.contains("taskPromptNotifier.cancel(row.id)"))
    }

    @Test
    fun `the notifier still posts with autoCancel false and the row is not consumed by viewing`() {
        val notifier = sourceFile(
            "app/src/main/java/com/newoether/agora/service/TaskPromptNotifier.kt",
        )
        val activity = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/automation/TaskConfirmationActivity.kt",
        )
        // The zombie fix is the activity cancel, not a flag flip: the notification must
        // stay autoCancel(false) so a tap that never reaches the activity (reboot race,
        // activity destroyed) does not silently consume the only shade entry.
        assertTrue(notifier.contains("setAutoCancel(false)"))
        // Viewing must not consume the row — no acknowledge/dismiss call on the load path.
        val load = activity.substringAfter("private fun load(", "")
        assertFalse(load.contains("acknowledge(") && load.substringBefore("private fun resolve(").contains("dismiss("))
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
