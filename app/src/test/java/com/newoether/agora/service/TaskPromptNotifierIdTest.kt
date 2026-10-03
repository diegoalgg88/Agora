package com.newoether.agora.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TaskPromptNotifier notification-ID contract tests (plan PLAN-20261002-TASK-CONFIRM).
 * The ID derives from the confirmation ID so a snooze re-fire replaces the previous
 * notification instead of stacking. The 51000 base deliberately sits far above the
 * pre-existing IDs (1, 424, and the 1001 that HeartbeatNotifier and AutoBackupManager
 * already collide on — that collision is a separate issue, not inherited here).
 *
 * The computation is a pure companion function, so the test exercises the production code
 * directly without instantiating the Android-dependent notifier.
 */
class TaskPromptNotifierIdTest {

    private fun idFor(confirmationId: String): Int =
        TaskPromptNotifier.notificationIdFor(confirmationId)

    @Test
    fun sameConfirmationYieldsSameId() {
        assertEquals(idFor("conf-a"), idFor("conf-a"))
    }

    @Test
    fun idsStayWithinTheDedicatedBaseRange() {
        // Every key (including negative hashes) lands in [51000, 51000 + 0xFFFFF], strictly
        // above every existing Agora notification ID (1, 424, 1001).
        val keys = listOf("", "conf-a", "conf-b", "\u0000", "Aa", "BB") +
            List(500) { java.util.UUID.randomUUID().toString() }
        for (key in keys) {
            assertTrue(idFor(key) in 51000..(51000 + 0xFFFFF))
        }
        assertTrue(TaskPromptNotifier.NOTIFICATION_ID_BASE > 1001)
    }

    @Test
    fun baseIsAboveAllExistingNotificationIds() {
        // 1 = AgoraForegroundService, 424 = LiveVoiceForegroundService, 1001 = heartbeat
        // (colliding with AutoBackupManager — pre-existing, out of scope).
        assertTrue(TaskPromptNotifier.NOTIFICATION_ID_BASE > 1001)
        assertTrue(TaskPromptNotifier.NOTIFICATION_ID_BASE > 424)
        assertTrue(TaskPromptNotifier.NOTIFICATION_ID_BASE > 1)
    }
}
