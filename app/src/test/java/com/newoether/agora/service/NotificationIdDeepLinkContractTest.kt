package com.newoether.agora.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notification identity + deep-link contract (plan PLAN-20261010-NOTIFICATION-FIXES, Fase 1):
 *
 * - H-N1: `HeartbeatNotifier` and `AutoBackupManager` must post on DIFFERENT ids.
 *   Android keys `notify` by (package, tag, id) — the channel does NOT isolate ids,
 *   so a shared value means a backup run silently replaces the heartbeat failure
 *   alert (observed: both posted 1001).
 * - H-N2: the heartbeat failure alert must deep-link into the heartbeat conversation
 *   via the device-verified extra pattern (`EXTRA_CONVERSATION_ID` + `agora://conversation/`),
 *   the same shape `AgoraForegroundService.createPendingIntent` uses. The old
 *   `OPEN_HEARTBEAT` action had no handler (MainActivity's `handleNavigationIntent`
 *   only reads `EXTRA_CONVERSATION_ID`).
 * - H-N3: the heartbeat PendingIntent must use its own requestCode (the notification
 *   id), never a bare 0 shared with other app PendingIntents — after H-N2 the
 *   heartbeat intent otherwise becomes `filterEquals`-identical to AutoBackup's,
 *   and FLAG_UPDATE_CURRENT would let them clobber each other.
 */
class NotificationIdDeepLinkContractTest {

    @Test
    fun `heartbeat and auto backup notification ids do not collide`() {
        val heartbeatId = HeartbeatNotifier.NOTIFICATION_ID
        val backupSource = sourceFile("data/AutoBackupManager.kt")
        val backupId = extractConst(backupSource, "NOTIFICATION_ID")
            ?: error("AutoBackupManager.NOTIFICATION_ID not found")
        assertNotEquals(
            "notify(tag,id) key ignores the channel: equal ids let a backup run replace the heartbeat failure alert",
            heartbeatId,
            backupId,
        )
    }

    @Test
    fun `heartbeat failure alert deep-links into the heartbeat conversation`() {
        val source = sourceFile("service/HeartbeatNotifier.kt")
        assertTrue(source.contains("MainActivity.EXTRA_CONVERSATION_ID"))
        assertTrue(source.contains(".authority(\"conversation\")"))
        assertTrue(source.contains("putExtra(MainActivity.EXTRA_CONVERSATION_ID, it)"))
        // The dead action is gone — no handler ever consumed it. (Match the assignment,
        // not the string: the class KDoc legitimately mentions the removed action name.)
        assertFalse(source.contains("action = \"com.newoether.agora.OPEN_HEARTBEAT\""))
    }

    @Test
    fun `heartbeat pending intent uses its own request code, never zero`() {
        val source = sourceFile("service/HeartbeatNotifier.kt")
        val pendingIntentBlock = source.substringAfter("PendingIntent.getActivity(")
        assertTrue(
            "requestCode must derive from the notification id so it cannot collide with the bare-0 requestCodes other posters use",
            pendingIntentBlock.contains("notificationId"),
        )
        assertFalse(pendingIntentsWithBareZeroRequestCode(source))
    }

    @Test
    fun `scheduler passes the heartbeat conversation id to the notifier`() {
        val scheduler = sourceFile("automation/HeartbeatScheduler.kt")
        assertTrue(
            "the call-site must forward the resolved heartbeat conversation id",
            scheduler.contains("sendHeartbeatNotification(resultText, heartbeatConversationId)"),
        )
        assertTrue(
            scheduler.contains("sendHeartbeatNotification(\"Heartbeat Check\", message, conversationId)"),
        )
    }

    /** True when any PendingIntent.getActivity call still passes the literal 0 requestCode. */
    private fun pendingIntentsWithBareZeroRequestCode(source: String): Boolean =
        Regex("""PendingIntent\.getActivity\(\s*\w+,\s*0,""").containsMatchIn(source)

    private fun extractConst(source: String, name: String): Int? =
        Regex("""const val $name\s*=\s*(\d+)""").find(source)?.groupValues?.get(1)?.toIntOrNull()

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, "app/src/main/java/com/newoether/agora/$relativePath")
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
