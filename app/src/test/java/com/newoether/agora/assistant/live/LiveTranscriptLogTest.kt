package com.newoether.agora.assistant.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavior of the pure live-caption state: turn folding, empty-side dropping, barge-in. */
class LiveTranscriptLogTest {

    @Test
    fun `in-flight transcript stays pending until the turn commits`() {
        val log = LiveTranscriptLog()
        log.onTranscript("hola", "")
        assertTrue(log.committedLines.isEmpty())
        assertEquals("hola", log.pendingUserLine)
        assertEquals("", log.pendingModelLine)
    }

    @Test
    fun `commit folds both sides into history and clears the pending slots`() {
        val log = LiveTranscriptLog()
        log.onTranscript("hola", "¿qué necesitas?")
        log.commitTurn()
        assertEquals(2, log.committedLines.size)
        assertEquals(LiveTranscriptLog.Side.USER, log.committedLines[0].side)
        assertEquals("hola", log.committedLines[0].text)
        assertEquals(LiveTranscriptLog.Side.MODEL, log.committedLines[1].side)
        assertEquals("¿qué necesitas?", log.committedLines[1].text)
        assertEquals("", log.pendingUserLine)
        assertEquals("", log.pendingModelLine)
    }

    @Test
    fun `empty sides are dropped on commit like the persisted turn`() {
        val log = LiveTranscriptLog()
        log.onTranscript("", "solo el modelo habló")
        log.commitTurn()
        assertEquals(1, log.committedLines.size)
        assertEquals(LiveTranscriptLog.Side.MODEL, log.committedLines[0].side)
    }

    @Test
    fun `barge-in resets the model side when the controller reports it cleared`() {
        val log = LiveTranscriptLog()
        log.onTranscript("interrumpo", "respuesta parcial…")
        assertEquals("respuesta parcial…", log.pendingModelLine)
        // Controller clears the interrupted model text; the log mirrors it.
        log.onTranscript("interrumpo", "")
        assertEquals("", log.pendingModelLine)
        assertEquals("interrumpo", log.pendingUserLine)
    }

    @Test
    fun `successive updates replace the turn's pending lines instead of appending`() {
        val log = LiveTranscriptLog()
        log.onTranscript("bue", "")
        log.onTranscript("buenos días", "")
        assertEquals("buenos días", log.pendingUserLine)
    }

    @Test
    fun `committing with nothing said keeps history unchanged`() {
        val log = LiveTranscriptLog()
        log.commitTurn()
        assertTrue(log.committedLines.isEmpty())
        assertTrue(!log.isNotEmpty)
    }
}
