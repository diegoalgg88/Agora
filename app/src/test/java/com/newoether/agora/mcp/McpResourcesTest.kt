package com.newoether.agora.mcp

import kotlinx.serialization.json.JsonNull
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

class McpResourcesTest {
    private fun page(vararg items: JsonObject): JsonObject = buildJsonObject {
        put("resources", buildJsonArray { items.forEach(::add) })
    }

    private fun item(
        uri: String? = "resource://docs",
        name: String? = null,
        title: String? = null,
        description: String? = null,
        mimeType: String? = null,
    ) = buildJsonObject {
        if (uri != null) put("uri", uri)
        if (name != null) put("name", name)
        if (title != null) put("title", title)
        if (description != null) put("description", description)
        if (mimeType != null) put("mimeType", mimeType)
    }

    @Test
    fun `title is the display name and name is the fallback before the uri`() {
        val resources = parseMcpResourcesPage(
            page(
                item(uri = "resource://a", name = "a-name", title = "A Title"),
                item(uri = "resource://b", name = "b-name"),
                item(uri = "resource://c"),
            ),
        )

        assertEquals(listOf("A Title", "b-name", "resource://c"), resources?.map { it.name })
        assertEquals(listOf("resource://a", "resource://b", "resource://c"), resources?.map { it.uri })
    }

    @Test
    fun `entries without a usable uri or object shape are skipped`() {
        val raw = buildJsonObject {
            put(
                "resources",
                buildJsonArray {
                    add(item(uri = null, name = "no uri"))
                    add(item(uri = "   ", name = "blank uri"))
                    add(buildJsonObject { put("uri", JsonNull) })
                    add(JsonPrimitive("not an object"))
                    add(item(uri = "resource://kept", name = "kept"))
                },
            )
        }

        val resources = parseMcpResourcesPage(raw)

        assertEquals(listOf("resource://kept"), resources?.map { it.uri })
    }

    @Test
    fun `a result without a resources array is rejected instead of treated as empty`() {
        assertNull(parseMcpResourcesPage(JsonObject(emptyMap())))
        assertNull(parseMcpResourcesPage(buildJsonObject { put("resources", "nope") }))
        assertEquals(emptyList<McpRemoteResource>(), parseMcpResourcesPage(page()))
    }

    @Test
    fun `description and mime type are trimmed and server text is bounded`() {
        val resources = parseMcpResourcesPage(
            page(
                item(
                    uri = "resource://" + "u".repeat(5000),
                    name = "n".repeat(5000),
                    description = "  " + "d".repeat(5000) + "  ",
                    mimeType = " text/markdown ",
                ),
                item(uri = "resource://blank", description = "   ", mimeType = ""),
            ),
        )!!

        assertTrue(resources[0].uri.length <= 2048)
        assertEquals(500, resources[0].name.length)
        assertEquals(500, resources[0].description?.length)
        assertEquals("text/markdown", resources[0].mimeType)
        assertNull(resources[1].description)
        assertNull(resources[1].mimeType)
    }

    @Test
    fun `ui scheme documents are flagged as MCP App documents`() {
        assertTrue(McpRemoteResource(uri = "ui://tomtom-map/dynamic-map/app.html", name = "map").isMcpAppDocument)
        assertFalse(McpRemoteResource(uri = "resource://mapbox-docs", name = "docs").isMcpAppDocument)
    }

    @Test
    fun `resources are only requested from servers that declare the capability`() {
        fun initialize(capabilities: JsonObject?) = buildJsonObject {
            put("protocolVersion", "2025-11-25")
            if (capabilities != null) put("capabilities", capabilities)
        }

        assertTrue(
            mcpServerMayListResources(
                initialize(
                    buildJsonObject {
                        put("tools", JsonObject(emptyMap()))
                        put("resources", buildJsonObject { put("listChanged", true) })
                    },
                ),
            ),
        )
        assertTrue(
            mcpServerMayListResources(initialize(buildJsonObject { put("resources", JsonObject(emptyMap())) })),
        )
        // Declared capabilities without resources: never asked, so it cannot fail a refresh.
        assertFalse(
            mcpServerMayListResources(initialize(buildJsonObject { put("tools", JsonObject(emptyMap())) })),
        )
        assertFalse(mcpServerMayListResources(initialize(JsonObject(emptyMap()))))
        // No capabilities object at all is unknown, so it is tried.
        assertTrue(mcpServerMayListResources(initialize(null)))
    }

    @Test
    fun `a snapshot defaults to no resources so older call sites keep working`() {
        val snapshot = McpServerSnapshot(serverId = "s")

        assertEquals(emptyList<McpRemoteResource>(), snapshot.resources)
        assertNotNull(snapshot.copy(status = McpConnectionStatus.CONNECTED).resources)
    }

    @Test
    fun `the per server cap is large enough to be useful but bounded`() {
        assertEquals(500, MAX_MCP_RESOURCES)
    }
}
