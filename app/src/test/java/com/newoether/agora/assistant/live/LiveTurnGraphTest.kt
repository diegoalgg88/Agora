package com.newoether.agora.assistant.live

import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chain shape of committed Live turns: linear ancestry inside one conversation. */
class LiveTurnGraphTest {

    private fun ids(): () -> String {
        var next = 0
        return { "id-${next++}" }
    }

    @Test
    fun `first turn has no parents and selects only the user to model edge`() {
        val graph = buildLiveTurnGraph("c1", null, null, "hola", "buenas", "m", now = 100L, newId = ids())
        assertNull(graph.userMessage.parentId)
        assertNull(graph.run.parentRunId)
        assertEquals(graph.userMessage.id, graph.modelMessage.parentId)
        assertEquals(mapOf<String?, String>(graph.userMessage.id to graph.modelMessage.id), graph.selectionUpdates)
        assertEquals(RunStatus.COMPLETED, graph.run.status)
        assertNull(graph.run.activeSlot)
        assertEquals(Participant.USER, graph.userMessage.participant)
        assertEquals(Participant.MODEL, graph.modelMessage.participant)
    }

    @Test
    fun `three turns form one linear chain inside a single conversation`() {
        val newId = ids()
        var leafMessage: String? = null
        var leafRun: String? = null
        val graphs = (1..3).map { n ->
            buildLiveTurnGraph("c1", leafMessage, leafRun, "u$n", "m$n", "m", now = n * 10L, newId = newId)
                .also {
                    leafMessage = it.modelMessage.id
                    leafRun = it.run.id
                }
        }
        assertTrue(graphs.all { g ->
            g.run.conversationId == "c1" &&
                g.userMessage.conversationId == "c1" &&
                g.modelMessage.conversationId == "c1"
        })
        assertEquals(graphs[0].modelMessage.id, graphs[1].userMessage.parentId)
        assertEquals(graphs[1].modelMessage.id, graphs[2].userMessage.parentId)
        assertEquals(graphs[0].run.id, graphs[1].run.parentRunId)
        assertEquals(graphs[1].run.id, graphs[2].run.parentRunId)
        // Selection map is parent → selected child: chaining turn 3 onto turn 2's MODEL row
        // re-points that row at turn 3's USER message (not at turn 2's own USER row).
        assertEquals(graphs[2].userMessage.id, graphs[2].selectionUpdates[graphs[1].modelMessage.id])
    }

    @Test
    fun `empty model reply is preserved for a flushed dangling turn`() {
        val graph = buildLiveTurnGraph("c1", null, null, "sin respuesta", "", "m", now = 1L, newId = ids())
        assertEquals("sin respuesta", graph.userMessage.text)
        assertEquals("", graph.modelMessage.text)
    }
}
