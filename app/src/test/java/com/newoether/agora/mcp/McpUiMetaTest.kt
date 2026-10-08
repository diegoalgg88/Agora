package com.newoether.agora.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class McpUiMetaTest {
    private fun uiMeta(uri: String? = null, visibility: List<String>? = null) = buildJsonObject {
        put(
            "ui",
            buildJsonObject {
                if (uri != null) put("resourceUri", uri)
                if (visibility != null) {
                    put("visibility", buildJsonArray { visibility.forEach { add(JsonPrimitive(it)) } })
                }
            },
        )
    }

    private fun descriptor(visibility: Set<String>) = McpToolDescriptor(
        publicName = "mcp_s_tool",
        serverId = "s",
        serverName = "S",
        remote = McpRemoteTool(
            name = "tool",
            description = "",
            inputSchema = JsonObject(emptyMap()),
            uiResourceUri = "ui://s/app",
            uiVisibility = visibility,
        ),
    )

    @Test
    fun nestedResourceUriAndVisibilityAreParsed() {
        val parsed = parseMcpToolUiMeta(uiMeta("ui://weather/dashboard", listOf("app")))

        assertEquals("ui://weather/dashboard", parsed.resourceUri)
        assertEquals(setOf("app"), parsed.visibility)
    }

    @Test
    fun legacyFlatKeyIsUsedOnlyWhenNestedKeyIsAbsent() {
        val legacy = buildJsonObject { put("ui/resourceUri", "ui://legacy/app") }
        assertEquals("ui://legacy/app", parseMcpToolUiMeta(legacy).resourceUri)

        val both = buildJsonObject {
            put("ui/resourceUri", "ui://legacy/app")
            put("ui", buildJsonObject { put("resourceUri", "ui://nested/app") })
        }
        assertEquals("ui://nested/app", parseMcpToolUiMeta(both).resourceUri)
    }

    @Test
    fun nonUiSchemesAndEmptyUrisAreRejected() {
        assertNull(parseMcpToolUiMeta(uiMeta("https://evil.example/app")).resourceUri)
        assertNull(parseMcpToolUiMeta(uiMeta("ui://")).resourceUri)
        // An invalid nested value must not mask a valid legacy value.
        val mixed = buildJsonObject {
            put("ui", buildJsonObject { put("resourceUri", "https://evil.example/app") })
            put("ui/resourceUri", "ui://legacy/app")
        }
        assertEquals("ui://legacy/app", parseMcpToolUiMeta(mixed).resourceUri)
    }

    @Test
    fun missingOrUnknownVisibilityFallsBackToModelAndApp() {
        assertEquals(McpUiVisibility.DEFAULT, parseMcpToolUiMeta(null).visibility)
        assertEquals(McpUiVisibility.DEFAULT, parseMcpToolUiMeta(uiMeta("ui://a/b")).visibility)
        assertEquals(
            McpUiVisibility.DEFAULT,
            parseMcpToolUiMeta(uiMeta("ui://a/b", listOf("nobody", ""))).visibility,
        )
        assertEquals(
            setOf("model"),
            parseMcpToolUiMeta(uiMeta("ui://a/b", listOf("model", "nobody"))).visibility,
        )
    }

    @Test
    fun descriptorVisibilityFlagsFollowAudiences() {
        val appOnly = descriptor(setOf("app"))
        assertFalse(appOnly.isModelVisible)
        assertTrue(appOnly.isAppVisible)

        val modelOnly = descriptor(setOf("model"))
        assertTrue(modelOnly.isModelVisible)
        assertFalse(modelOnly.isAppVisible)

        val both = descriptor(McpUiVisibility.DEFAULT)
        assertTrue(both.isModelVisible)
        assertTrue(both.isAppVisible)
    }

    private fun resourceResult(
        uri: String = "ui://s/app",
        mimeType: String? = MCP_UI_MIME_TYPE,
        text: String? = null,
        blob: String? = null,
        meta: JsonObject? = null,
    ) = buildJsonObject {
        put(
            "contents",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("uri", uri)
                        if (mimeType != null) put("mimeType", mimeType)
                        if (text != null) put("text", text)
                        if (blob != null) put("blob", blob)
                        if (meta != null) put("_meta", meta)
                    },
                )
            },
        )
    }

    @Test
    fun textResourceIsAcceptedWithItsUiMeta() {
        val csp = buildJsonObject { put("csp", buildJsonObject { put("connectDomains", buildJsonArray {}) }) }
        val resource = parseMcpUiResourceContents(
            "ui://s/app",
            resourceResult(text = "<html>ok</html>", meta = buildJsonObject { put("ui", csp) }),
        )

        assertNotNull(resource)
        assertEquals("<html>ok</html>", resource!!.html)
        assertEquals(csp, resource.uiMeta)
    }

    @Test
    fun base64BlobResourceIsDecoded() {
        val blob = Base64.getEncoder().encodeToString("<html>blob</html>".toByteArray())

        val resource = parseMcpUiResourceContents("ui://s/app", resourceResult(blob = blob))

        assertEquals("<html>blob</html>", resource?.html)
    }

    @Test
    fun resourceValidationFailsClosed() {
        // wrong MIME type, missing MIME type, different URI, blank html, no body, bad base64
        assertNull(parseMcpUiResourceContents("ui://s/app", resourceResult(mimeType = "text/html", text = "x")))
        assertNull(parseMcpUiResourceContents("ui://s/app", resourceResult(mimeType = null, text = "x")))
        assertNull(parseMcpUiResourceContents("ui://s/app", resourceResult(uri = "ui://s/other", text = "x")))
        assertNull(parseMcpUiResourceContents("ui://s/app", resourceResult(text = "   ")))
        assertNull(parseMcpUiResourceContents("ui://s/app", resourceResult()))
        assertNull(parseMcpUiResourceContents("ui://s/app", resourceResult(blob = "A")))
        assertNull(parseMcpUiResourceContents("ui://s/app", buildJsonObject {}))
    }

    @Test
    fun mimeTypeComparisonIgnoresWhitespaceAndCase() {
        val resource = parseMcpUiResourceContents(
            "ui://s/app",
            resourceResult(mimeType = "Text/HTML; profile=mcp-app", text = "<p>x</p>"),
        )

        assertEquals("<p>x</p>", resource?.html)
    }

    @Test
    fun oversizeResourcesAreRejected() {
        val tooBigText = "a".repeat(MAX_MCP_UI_RESOURCE_BYTES + 1)
        assertNull(parseMcpUiResourceContents("ui://s/app", resourceResult(text = tooBigText)))

        val tooBigBlob = Base64.getEncoder()
            .encodeToString(ByteArray(MAX_MCP_UI_RESOURCE_BYTES + 1) { 'a'.code.toByte() })
        assertNull(parseMcpUiResourceContents("ui://s/app", resourceResult(blob = tooBigBlob)))

        val atLimit = "a".repeat(MAX_MCP_UI_RESOURCE_BYTES)
        assertEquals(
            MAX_MCP_UI_RESOURCE_BYTES,
            parseMcpUiResourceContents("ui://s/app", resourceResult(text = atLimit))?.html?.length,
        )
    }
}
