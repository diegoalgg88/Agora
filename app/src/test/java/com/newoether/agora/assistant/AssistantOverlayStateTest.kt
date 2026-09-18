package com.newoether.agora.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-policy tests for the assistant overlay's dictation prompt joining. */
class AssistantOverlayStateTest {

    @Test
    fun `spoken results replace only the dictated segment when a typed prefix exists`() {
        assertEquals(
            "note that hello world",
            AssistantOverlayState.joinDictationPrompt("note that", "hello world"),
        )
    }

    @Test
    fun `prefix punctuation and spacing are normalized before joining`() {
        assertEquals(
            "note that hello",
            AssistantOverlayState.joinDictationPrompt("note that  ", "hello"),
        )
    }

    @Test
    fun `dictation without a prefix lands unchanged`() {
        assertEquals("hello", AssistantOverlayState.joinDictationPrompt(null, "hello"))
        assertEquals("hello", AssistantOverlayState.joinDictationPrompt("", "hello"))
        assertEquals("hello", AssistantOverlayState.joinDictationPrompt("   ", "hello"))
    }

    @Test
    fun `successive partials converge on the spoken text with the same prefix`() {
        val prefix = "question "
        var prompt = AssistantOverlayState.joinDictationPrompt(prefix, "what")
        assertEquals("question what", prompt)
        prompt = AssistantOverlayState.joinDictationPrompt(prefix, "what is this")
        assertEquals("question what is this", prompt)
    }
}
