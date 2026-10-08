package com.newoether.agora.viewmodel

import com.newoether.agora.api.StreamEvent
import com.newoether.agora.mcp.McpUiReference
import com.newoether.agora.tool.ToolExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationToolUiReferenceTest {
    private fun overlayWithRunningCall(): GenerationToolOverlay {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String) = null
            },
            providerName = "provider",
        )
        overlay.upsert("stream", "call", "tool", "{}", null)
        overlay.start(call())
        return overlay
    }

    private fun call() = StreamEvent.ToolCallRequest(
        id = "call",
        name = "tool",
        arguments = "{}",
        streamKey = "stream",
        signature = null,
        responseOutputItems = emptyList(),
    )

    @Test
    fun `completed MCP App result persists only the document pointer on segment and tool call`() {
        val overlay = overlayWithRunningCall()

        val completed = overlay.complete(
            call(),
            ToolExecutionResult(
                text = "plain text fallback",
                structuredContent = """{"temp":21}""",
                uiResource = McpUiReference(serverId = "server-1", resourceUri = "ui://weather/app"),
            ),
        )

        assertEquals("server-1", completed.segment.toolUiServerId)
        assertEquals("ui://weather/app", completed.segment.toolUiResourceUri)
        assertEquals("server-1", completed.data.uiServerId)
        assertEquals("ui://weather/app", completed.data.uiResourceUri)
        // The model-facing result is untouched by the UI pointer.
        assertEquals("plain text fallback", completed.data.result)
        assertEquals("""{"temp":21}""", completed.segment.toolStructuredResult)
    }

    @Test
    fun `result without an MCP App leaves the pointer empty`() {
        val overlay = overlayWithRunningCall()

        val completed = overlay.complete(call(), ToolExecutionResult(text = "done"))

        assertNull(completed.segment.toolUiServerId)
        assertNull(completed.segment.toolUiResourceUri)
        assertNull(completed.data.uiServerId)
        assertNull(completed.data.uiResourceUri)
    }

    @Test
    fun `completed segment in the overlay snapshot keeps the pointer`() {
        val overlay = overlayWithRunningCall()
        overlay.complete(
            call(),
            ToolExecutionResult(
                text = "ok",
                uiResource = McpUiReference("server-1", "ui://a/b"),
            ),
        )

        val tool = overlay.snapshot().single { it.type == "tool" }

        assertEquals("ui://a/b", tool.toolUiResourceUri)
    }
}
