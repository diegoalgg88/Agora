package com.newoether.agora.webui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.util.appLanguageResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The web receives the app's own layout decisions and card text, in the phone's language. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WebUiMessagePresentationTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun display(
        language: String = "en",
        toolMode: String = ToolCallDisplayModes.DEFAULT,
    ) = WebDisplayContext(
        resources = context.appLanguageResources(language),
        toolCallDisplayMode = toolMode,
        thinkingSegmentDisplayMode = ThinkingSegmentDisplayModes.DEFAULT,
        autoExpandActiveGroup = true,
        parseInlineDollarMath = false,
    )

    private fun model(status: MessageStatus, vararg segments: MessageSegment, text: String = "") =
        ChatMessage(
            id = "m",
            text = text,
            participant = Participant.MODEL,
            status = status,
            segments = segments.toList(),
        )

    @Test
    fun `live thinking group carries a timer base and loading icon`() {
        val message = model(
            MessageStatus.THINKING,
            MessageSegment(type = "thought", content = "plan", durationMs = 5_000),
        )
        val presentation = webPresentation(message, isStreaming = true, display())!!
        val group = (presentation.blocks.single() as WebTimelineBlock.Group).group
        assertEquals(5_000L, group.liveBaseMs)
        assertEquals("LOADING", group.icon)
        assertEquals(
            context.appLanguageResources("en").getString(R.string.thinking_for_seconds_ellipsis, 5),
            group.title,
        )
        assertTrue(group.items.single().streaming)
    }

    @Test
    fun `finished message uses static title then answer block`() {
        val resources = context.appLanguageResources("en")
        val message = model(
            MessageStatus.SUCCESS,
            MessageSegment(type = "thought", content = "plan", durationMs = 12_000),
            MessageSegment(type = "answer", content = "hi"),
            text = "hi",
        )
        val presentation = webPresentation(message, isStreaming = false, display())!!
        assertTrue(presentation.useTimeline)
        val group = (presentation.blocks[0] as WebTimelineBlock.Group).group
        assertNull(group.liveBaseMs)
        assertEquals(resources.getString(R.string.thought_for_seconds, 12), group.title)
        assertEquals(resources.getString(R.string.tool_thinking), group.items.single().title)
        assertEquals("hi", (presentation.blocks[1] as WebTimelineBlock.Answer).text.markdown)
        assertNull(presentation.inlineTerminalText)
    }

    @Test
    fun `compact mode sends one block and the answer body`() {
        val message = model(
            MessageStatus.SUCCESS,
            MessageSegment(type = "thought", content = "plan", durationMs = 2_000),
            MessageSegment(type = "answer", content = "hi"),
            text = "hi",
        )
        val presentation =
            webPresentation(message, isStreaming = false, display(toolMode = ToolCallDisplayModes.COMPACT))!!
        assertTrue(!presentation.useTimeline)
        assertTrue(presentation.blocks.isEmpty())
        assertEquals("compact", presentation.compact!!.key)
        assertEquals("hi", presentation.answer!!.markdown)
    }

    @Test
    fun `stopped message without answer shows localized inline text`() {
        val message = model(MessageStatus.STOPPED)
        val zh = display(language = "zh")
        val presentation = webPresentation(message, isStreaming = false, zh)!!
        assertEquals(zh.resources.getString(R.string.generation_stopped), presentation.inlineTerminalText)
        assertNull(webPresentation(message.copy(participant = Participant.USER), false, zh))
    }

    @Test
    fun `display event follows the app language and serializes`() {
        val en = display().toEvent()
        val zh = display(language = "zh").toEvent()
        assertTrue(en.liveThinking.seconds.contains("%1\$d"))
        assertNotEquals(en.liveThinking.seconds, zh.liveThinking.seconds)
        val encoded = WebUiSync.json.encodeToString(WebSyncEvent.serializer(), zh)
        assertTrue(encoded.contains("\"type\":\"display\""))
    }
}
