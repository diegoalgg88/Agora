package com.newoether.agora.assistant

import android.app.assist.AssistStructure
import android.graphics.Bitmap
import android.content.Context
import com.newoether.agora.util.DebugLog
import java.io.File
import java.util.UUID

/**
 * Captures screen context delivered by the Assist API. Text comes from the view hierarchy
 * (`AssistStructure`), the image from `onHandleScreenshot`. Both are best-effort: apps with
 * `FLAG_SECURE` deliver nothing, and the system may withhold either piece at any time.
 * See `development/system-assistant.md`.
 */
object AssistContextCapture {

    private const val TAG = "AssistContextCapture"
    private const val MAX_TEXT_CHARS = 8_192

    /** Flattens the view hierarchy into plain text, truncated at [MAX_TEXT_CHARS]. */
    fun extractText(structure: AssistStructure?): String {
        if (structure == null) return ""
        val out = StringBuilder()
        for (i in 0 until structure.windowNodeCount) {
            walk(structure.getWindowNodeAt(i).rootViewNode, out)
            if (out.length >= MAX_TEXT_CHARS) break
        }
        return out.toString().take(MAX_TEXT_CHARS).trim()
    }

    private fun walk(node: AssistStructure.ViewNode?, out: StringBuilder) {
        if (node == null || out.length >= MAX_TEXT_CHARS) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            out.append(it).append('\n')
        }
        for (i in 0 until node.childCount) {
            walk(node.getChildAt(i), out)
        }
    }

    /** Persists the screenshot as PNG under app-private attachment storage; returns its path. */
    fun saveScreenshot(context: Context, bitmap: Bitmap): String? = runCatching {
        val dir = File(context.filesDir, "attachments/assistant")
        dir.mkdirs()
        val file = File(dir, "screen-${UUID.randomUUID()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.absolutePath
    }.onFailure { DebugLog.w(TAG, "Failed to persist assist screenshot", it) }.getOrNull()
}
