package com.newoether.agora.mcp.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpAppSandboxTest {
    private fun meta(vararg entries: Pair<String, List<String>>): JsonObject = buildJsonObject {
        put(
            "csp",
            buildJsonObject {
                entries.forEach { (key, values) ->
                    put(key, buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                }
            },
        )
    }

    @Test
    fun `origin is deterministic distinct per document and shaped like a dedicated host`() {
        val a = McpAppSandbox.origin("server-1", "ui://a/app")

        assertEquals(a, McpAppSandbox.origin("server-1", "ui://a/app"))
        assertNotEquals(a, McpAppSandbox.origin("server-2", "ui://a/app"))
        assertNotEquals(a, McpAppSandbox.origin("server-1", "ui://a/other"))
        assertTrue(Regex("""^https://[0-9a-f]{32}\.mcp-app\.invalid$""").matches(a))
    }

    @Test
    fun `server and uri cannot be shifted across the hash input boundary`() {
        // "ab" + "c" vs "a" + "bc" must not collide thanks to the separator.
        assertNotEquals(
            McpAppSandbox.origin("ab", "c"),
            McpAppSandbox.origin("a", "bc"),
        )
    }

    @Test
    fun `default CSP without metadata is the restrictive spec default`() {
        val csp = McpAppSandbox.buildCsp(null)

        assertTrue(csp.contains("default-src 'none'"))
        assertTrue(csp.contains("script-src 'self' 'unsafe-inline'"))
        assertTrue(csp.contains("connect-src 'none'"))
        assertTrue(csp.contains("frame-src 'none'"))
        assertTrue(csp.contains("object-src 'none'"))
        assertTrue(csp.contains("base-uri 'self'"))
        assertTrue(csp.contains("img-src 'self' data: blob:"))
        assertTrue(csp.contains("worker-src 'self' blob:"))
        assertFalse(csp.contains("http"))
    }

    @Test
    fun `declared domains reach only the directives the spec maps them to`() {
        val csp = McpAppSandbox.buildCsp(
            meta(
                "connectDomains" to listOf("https://api.example.com", "wss://live.example.com"),
                "resourceDomains" to listOf("https://cdn.example.com", "https://*.fonts.example.com"),
                "frameDomains" to listOf("https://player.example.com"),
                "baseUriDomains" to listOf("https://base.example.com"),
            ),
        )

        assertTrue(csp.contains("connect-src https://api.example.com wss://live.example.com"))
        assertTrue(csp.contains("script-src 'self' 'unsafe-inline' https://cdn.example.com https://*.fonts.example.com"))
        assertTrue(csp.contains("style-src 'self' 'unsafe-inline' https://cdn.example.com"))
        assertTrue(csp.contains("img-src 'self' data: blob: https://cdn.example.com"))
        assertTrue(csp.contains("worker-src 'self' blob: https://cdn.example.com https://*.fonts.example.com"))
        assertTrue(csp.contains("frame-src https://player.example.com"))
        assertTrue(csp.contains("base-uri https://base.example.com"))
        assertFalse(csp.contains("connect-src 'none'"))
        // A resource domain must never widen connect-src.
        assertFalse(Regex("connect-src[^;]*cdn\\.example\\.com").containsMatchIn(csp))
    }

    @Test
    fun `malformed or widening domains are dropped before reaching a header`() {
        val hostile = listOf(
            "https://ok.example.com",
            "https://evil.example.com; script-src *",
            "*",
            "https://*",
            "http://insecure.example.com",
            "https://a.example.com/path",
            "https://a b.example.com",
            "https://user@host.example.com",
            "'unsafe-eval'",
            "",
        )

        val csp = McpAppSandbox.buildCsp(meta("connectDomains" to hostile))

        assertTrue(csp.contains("connect-src https://ok.example.com;"))
        assertFalse(csp.contains("evil.example.com"))
        assertFalse(csp.contains("script-src *"))
        assertFalse(csp.contains("insecure.example.com"))
        assertFalse(csp.contains("unsafe-eval"))
        assertEquals(1, McpAppSandbox.domains(meta("connectDomains" to hostile), "connectDomains").size)
    }

    @Test
    fun `domain lists are deduplicated and capped`() {
        val many = (1..40).map { "https://h$it.example.com" } + "https://h1.example.com"

        val domains = McpAppSandbox.domains(meta("connectDomains" to many), "connectDomains")

        assertEquals(16, domains.size)
        assertEquals(domains.distinct(), domains)
    }

    @Test
    fun `page headers carry the CSP and deny device permissions`() {
        val headers = McpAppSandbox.pageHeaders(null)

        assertEquals(McpAppSandbox.buildCsp(null), headers["Content-Security-Policy"])
        assertEquals(McpAppSandbox.PERMISSIONS_POLICY, headers["Permissions-Policy"])
        assertEquals("no-store", headers["Cache-Control"])
        assertTrue(McpAppSandbox.PERMISSIONS_POLICY.contains("camera=()"))
        assertTrue(McpAppSandbox.PERMISSIONS_POLICY.contains("microphone=()"))
        assertTrue(McpAppSandbox.PERMISSIONS_POLICY.contains("geolocation=()"))
    }

    @Test
    fun `only the document inline data and declared origins are allowed through`() {
        val origin = McpAppSandbox.origin("s", "ui://a/app")
        val uiMeta = meta(
            "connectDomains" to listOf("https://api.example.com"),
            "resourceDomains" to listOf("https://*.cdn.example.com"),
        )
        fun allowed(url: String) = McpAppSandbox.isRequestAllowed(url, origin, uiMeta)

        assertTrue(allowed(McpAppSandbox.pageUrl(origin)))
        assertTrue(allowed("blob:$origin/1234"))
        assertTrue(allowed("data:image/png;base64,iVBORw0KGgo="))
        assertTrue(allowed("data:text/plain,hello world with spaces"))
        assertTrue(allowed("https://api.example.com/v1/weather"))
        assertTrue(allowed("wss://api.example.com/socket"))
        assertTrue(allowed("https://img.cdn.example.com/a.png"))

        // Other paths on the synthetic origin are not served.
        assertFalse(allowed("$origin/other.js"))
        assertFalse(allowed("blob:https://elsewhere.invalid/1"))
        // Undeclared hosts, wildcard apex, wrong scheme, wrong port.
        assertFalse(allowed("https://tracker.example.net/p.gif"))
        assertFalse(allowed("https://cdn.example.com/a.png"))
        assertFalse(allowed("http://api.example.com/v1"))
        assertFalse(allowed("https://api.example.com:8443/v1"))
        // Device and legacy schemes.
        assertFalse(allowed("file:///sdcard/secret"))
        assertFalse(allowed("content://com.android.providers/x"))
        assertFalse(allowed("javascript:alert(1)"))
        assertFalse(allowed("ftp://api.example.com/x"))
    }

    @Test
    fun `without declared domains nothing external is allowed`() {
        val origin = McpAppSandbox.origin("s", "ui://a/app")

        assertFalse(McpAppSandbox.isRequestAllowed("https://api.example.com/", origin, null))
        assertTrue(McpAppSandbox.isRequestAllowed(McpAppSandbox.pageUrl(origin), origin, null))
    }
}
