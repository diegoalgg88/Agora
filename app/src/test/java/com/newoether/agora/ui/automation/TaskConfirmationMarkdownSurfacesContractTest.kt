package com.newoether.agora.ui.automation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3 no-regression contract: only the Compose confirmation CARD renders markdown. The
 * notification and the in-chat banner are plain-text surfaces — Android BigTextStyle cannot
 * render markdown and the banner is a 2-line peek, so they must keep the shared plain
 * projection (bodyText) even now that the card renders rich markdown (2026-10-08).
 */
class TaskConfirmationMarkdownSurfacesContractTest {

    @Test
    fun `card renders markdown while notifier and banner stay plain`() {
        val card = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/automation/TaskConfirmationCard.kt",
        )
        val notifier = sourceFile(
            "app/src/main/java/com/newoether/agora/service/TaskPromptNotifier.kt",
        )
        val banner = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/composables/PendingTaskConfirmationsBanner.kt",
        )

        // The card is the only markdown surface of the three.
        assertTrue(card.contains("Markdown("))
        assertFalse(notifier.contains("Markdown("))
        assertFalse(banner.contains("Markdown("))
        // The notification keeps the plain-text BigTextStyle projection.
        assertTrue(notifier.contains("BigTextStyle"))
        // The banner keeps its 2-line plain peek.
        assertTrue(banner.contains("maxLines = 2"))
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
