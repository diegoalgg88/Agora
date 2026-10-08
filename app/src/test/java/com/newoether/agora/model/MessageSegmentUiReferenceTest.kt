package com.newoether.agora.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageSegmentUiReferenceTest {
    private val json = Json

    @Test
    fun `segment rows written before MCP Apps decode without a pointer`() {
        val old = """{"type":"tool","toolName":"t","toolResult":"r","toolState":"succeeded"}"""

        val segment = json.decodeFromString<MessageSegment>(old)

        assertNull(segment.toolUiServerId)
        assertNull(segment.toolUiResourceUri)
        assertEquals("r", segment.toolResult)
    }

    @Test
    fun `pointer survives a segment JSON round trip`() {
        val original = MessageSegment(
            type = "tool",
            toolName = "t",
            toolResult = "r",
            toolUiServerId = "server-1",
            toolUiResourceUri = "ui://a/b",
        )

        val decoded = json.decodeFromString<MessageSegment>(json.encodeToString(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `tool call data rows written before MCP Apps decode without a pointer`() {
        val old = """{"toolName":"t","arguments":"{}","result":"r"}"""

        val data = json.decodeFromString<ToolCallData>(old)

        assertNull(data.uiServerId)
        assertNull(data.uiResourceUri)
    }
}
