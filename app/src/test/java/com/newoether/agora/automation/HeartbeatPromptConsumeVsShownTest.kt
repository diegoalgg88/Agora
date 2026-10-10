package com.newoether.agora.automation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Consume-vs-shown contract: after a successful heartbeat the scheduler consumes
 * EXACTLY the items the prompt showed the model — never the whole pending queue.
 * A backlog larger than the per-channel render limit must survive for later
 * heartbeats; silently dropping items the model never saw loses user data
 * (development/automation.md §5 "exactly the snapshot the AI saw is removed").
 *
 * Pinned as a source contract because the scheduler's snapshot capture lives in a
 * private method: the assertion is that the snapshot keys are derived from the
 * SAME capped lists passed to the builder, using the SAME shared limits object.
 */
class HeartbeatPromptConsumeVsShownTest {

    @Test
    fun `builder and scheduler share one limits object`() {
        val builder = sourceFile("data/HeartbeatPromptBuilder.kt")
        val scheduler = sourceFile("automation/HeartbeatScheduler.kt")
        // The builder's render caps and the scheduler's snapshot caps both route
        // through HeartbeatShownLimits — there is no bare `.take(20)` left.
        assertTrue(builder.contains("object HeartbeatShownLimits"))
        assertTrue(builder.contains("take(HeartbeatShownLimits.SMS)"))
        assertTrue(builder.contains("take(HeartbeatShownLimits.NOTIFICATIONS)"))
        assertTrue(builder.contains("take(HeartbeatShownLimits.EMAILS)"))
        assertTrue(scheduler.contains("take(HeartbeatShownLimits.SMS)"))
        assertTrue(scheduler.contains("take(HeartbeatShownLimits.NOTIFICATIONS)"))
        assertTrue(scheduler.contains("take(HeartbeatShownLimits.EMAILS)"))
        assertEquals(
            "builder must not keep bare numeric caps on pending channels",
            0,
            Regex("""\.take\(20\)""").findAll(builderBodyWithoutLimits(builder)).count(),
        )
    }

    @Test
    fun `snapshot keys are derived from the capped lists the builder received`() {
        val scheduler = sourceFile("automation/HeartbeatScheduler.kt")
        val buildPrompt = scheduler.substringAfter("private suspend fun buildPrompt")
        // The snapshot key lists must map the SAME capped local lists that were passed
        // to buildHeartbeatPrompt — not a second, uncapped store read.
        assertTrue("smsIds = pendingSms.map" in buildPrompt)
        assertTrue("notificationKeys = pendingNotifications.map" in buildPrompt)
        assertTrue("emailKeys = pendingEmails.map" in buildPrompt)
        // Exactly one store snapshot read per channel inside buildPrompt.
        assertEquals(1, Regex("""smsStore\.getPendingSnapshot\(\)""").findAll(buildPrompt).count())
        assertEquals(1, Regex("""notificationStore\.getPendingSnapshot\(\)""").findAll(buildPrompt).count())
        assertEquals(1, Regex("""emailStore\.getPendingSnapshot\(\)""").findAll(buildPrompt).count())
    }

    private fun builderBodyWithoutLimits(builder: String): String =
        builder.substringAfter("object HeartbeatShownLimits").substringAfter("}")

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
