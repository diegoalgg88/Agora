package com.newoether.agora.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract: MainActivity's launch mode must keep notification deep-links deliverable.
 *
 * With the implicit standard launch mode, a notification PendingIntent while the task is
 * alive only triggers START_TASK_TO_FRONT and the intent (EXTRA_CONVERSATION_ID /
 * agora://conversation) is dropped — "Abrir conversación" re-showed the previously open
 * conversation instead of the task's conversation (verified on-device 2026-10-08).
 * singleTask guarantees onNewIntent delivery on the live instance.
 */
class MainActivityLaunchModeContractTest {

    @Test
    fun `MainActivity is singleTask so notification intents reach onNewIntent`() {
        val manifest = sourceFile("app/src/main/AndroidManifest.xml")
        val mainActivity = manifest
            .substringAfter("<activity")
            .substringBefore("</activity>")
            .substringBefore("<activity")
        assertTrue(mainActivity.contains("android:name=\".MainActivity\""))
        assertTrue(mainActivity.contains("android:launchMode=\"singleTask\""))
        // Android requires singleTask launcher-root candidates to keep the launcher filter,
        // and onNewIntent delivery depends on the activity remaining the exported entry point.
        assertTrue(mainActivity.contains("android.intent.action.MAIN"))
        assertTrue(mainActivity.contains("android.intent.category.LAUNCHER"))
        assertTrue(mainActivity.contains("android:exported=\"true\""))
        // The consumer of the delivered intents must stay wired.
        val main = sourceFile("app/src/main/java/com/newoether/agora/MainActivity.kt")
        assertTrue(main.contains("override fun onNewIntent(intent: Intent)"))
        assertTrue(main.contains("handleNavigationIntent(intent)"))
        assertTrue(main.contains("EXTRA_CONVERSATION_ID"))
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
