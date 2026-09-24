package com.newoether.agora.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatPromptRenderingTest {
    @Test
    fun heartbeatUserPromptRendersAsBadgeRow() {
        assertTrue(isHeartbeatPromptMessage(Participant.USER, "heartbeat"))
    }

    @Test
    fun nonUserParticipantsNeverRenderAsBadgeRow() {
        assertFalse(isHeartbeatPromptMessage(Participant.MODEL, "heartbeat"))
        assertFalse(isHeartbeatPromptMessage(Participant.ERROR, "heartbeat"))
    }

    @Test
    fun preV37AndOrdinaryKindsRenderAsOrdinaryBubble() {
        assertFalse(isHeartbeatPromptMessage(Participant.USER, null))
        assertFalse(isHeartbeatPromptMessage(Participant.USER, "chat"))
        assertFalse(isHeartbeatPromptMessage(Participant.USER, "task"))
        assertFalse(isHeartbeatPromptMessage(Participant.USER, "loop"))
        assertFalse(isHeartbeatPromptMessage(Participant.USER, "compact"))
    }
}
