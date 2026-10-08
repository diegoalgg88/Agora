package com.newoether.agora.mcp.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpAppBridgeRouterTest {
    private class FakePort : McpAppHostPort {
        val toolCalls = mutableListOf<Pair<String, JsonObject>>()
        val links = mutableListOf<String>()
        val heights = mutableListOf<Int>()
        val logs = mutableListOf<String>()
        var outcome = McpAppToolOutcome(text = "done", structuredContent = null, isError = false)
        var failure: Exception? = null
        var linkDecision = true
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun callTool(name: String, arguments: JsonObject): McpAppToolOutcome {
            gate?.await()
            failure?.let { throw it }
            toolCalls += name to arguments
            return outcome
        }

        override suspend fun openLink(url: String): Boolean {
            links += url
            return linkDecision
        }

        override fun onSizeChanged(heightCssPx: Int) {
            heights += heightCssPx
        }

        override fun onLog(message: String) {
            logs += message
        }
    }

    private val port = FakePort()
    private val toolInput = buildJsonObject { put("location", "NYC") }
    private val toolResult = buildJsonObject {
        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "72F") }) })
        put("isError", false)
    }
    private val hostContext = buildJsonObject { put("theme", "dark") }
    private val router = McpAppBridgeRouter(
        port = port,
        hostContext = hostContext,
        hostVersion = "9.9.9",
        toolInput = toolInput,
        toolResult = toolResult,
    )

    private fun request(id: Int, method: String, params: JsonObject = JsonObject(emptyMap())) =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()

    private fun notification(method: String, params: JsonObject = JsonObject(emptyMap())) =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }.toString()

    private fun parse(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun JsonObject.errorCode(): Int? = (get("error") as? JsonObject)?.get("code")?.jsonPrimitive?.int

    private fun JsonObject.errorMessage(): String? =
        (get("error") as? JsonObject)?.get("message")?.jsonPrimitive?.content

    private suspend fun handshake() {
        router.handle(request(1, "ui/initialize"))
        router.handle(notification("ui/notifications/initialized"))
    }

    private fun toolCallParams(name: String? = "refresh", arguments: Any? = buildJsonObject {}) =
        buildJsonObject {
            if (name != null) put("name", name)
            when (arguments) {
                is JsonObject -> put("arguments", arguments)
                is JsonArray -> put("arguments", arguments)
                null -> Unit
            }
        }

    @Test
    fun `initialize answers with the spec version host capabilities and context`() = runTest {
        val response = parse(router.handle(request(7, "ui/initialize")).single())

        assertEquals("2.0", response["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals(7, response["id"]?.jsonPrimitive?.int)
        val result = response["result"]!!.jsonObject
        assertEquals("2026-01-26", result["protocolVersion"]?.jsonPrimitive?.content)
        val capabilities = result["hostCapabilities"]!!.jsonObject
        assertNotNull(capabilities["serverTools"])
        assertNotNull(capabilities["openLinks"])
        assertNotNull(capabilities["logging"])
        // Not offered in v1: the router refuses these methods.
        assertNull(capabilities["serverResources"])
        assertEquals("Agora", result["hostInfo"]!!.jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("9.9.9", result["hostInfo"]!!.jsonObject["version"]?.jsonPrimitive?.content)
        assertEquals(hostContext, result["hostContext"])
        assertFalse(router.isReady)
    }

    @Test
    fun `string request ids are echoed unchanged`() = runTest {
        val raw = """{"jsonrpc":"2.0","id":"abc-1","method":"ping"}"""

        val response = parse(router.handle(raw).single())

        assertEquals("abc-1", response["id"]?.jsonPrimitive?.content)
        assertTrue(response["id"]?.jsonPrimitive?.isString == true)
    }

    @Test
    fun `host sends nothing before initialized and then tool input precedes tool result`() = runTest {
        assertTrue(router.handle(request(1, "ui/initialize")).size == 1)
        assertFalse(router.isReady)

        val outgoing = router.handle(notification("ui/notifications/initialized")).map(::parse)

        assertTrue(router.isReady)
        assertEquals(
            listOf("ui/notifications/tool-input", "ui/notifications/tool-result"),
            outgoing.map { it["method"]?.jsonPrimitive?.content },
        )
        assertTrue(outgoing.all { it["id"] == null })
        assertEquals(toolInput, outgoing[0]["params"]!!.jsonObject["arguments"])
        assertEquals(toolResult, outgoing[1]["params"])
    }

    @Test
    fun `initialized without a prior initialize or repeated initialized emits nothing`() = runTest {
        assertTrue(router.handle(notification("ui/notifications/initialized")).isEmpty())
        assertFalse(router.isReady)

        router.handle(request(1, "ui/initialize"))
        assertEquals(2, router.handle(notification("ui/notifications/initialized")).size)
        assertTrue(router.handle(notification("ui/notifications/initialized")).isEmpty())
    }

    @Test
    fun `requests before the handshake completes are refused and never reach the port`() = runTest {
        val beforeInit = parse(router.handle(request(1, "tools/call", toolCallParams())).single())
        assertEquals(-32002, beforeInit.errorCode())

        router.handle(request(2, "ui/initialize"))
        val beforeInitialized = parse(router.handle(request(3, "tools/call", toolCallParams())).single())
        assertEquals(-32002, beforeInitialized.errorCode())

        assertTrue(port.toolCalls.isEmpty())
    }

    @Test
    fun `ping is answered at any phase`() = runTest {
        val response = parse(router.handle(request(5, "ping")).single())

        assertEquals(JsonObject(emptyMap()), response["result"])
    }

    @Test
    fun `tools call forwards name and arguments and returns a CallToolResult`() = runTest {
        handshake()
        port.outcome = McpAppToolOutcome(
            text = "refreshed",
            structuredContent = """{"temp":21}""",
            isError = false,
        )

        val response = parse(
            router.handle(
                request(
                    9,
                    "tools/call",
                    toolCallParams("refresh", buildJsonObject { put("location", "LA") }),
                ),
            ).single(),
        )

        assertEquals(listOf("refresh" to buildJsonObject { put("location", "LA") }), port.toolCalls)
        val result = response["result"]!!.jsonObject
        val content = result["content"] as JsonArray
        assertEquals("refreshed", content.single().jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals(buildJsonObject { put("temp", 21) }, result["structuredContent"])
        assertFalse(result["isError"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `tool errors are reported as isError results not protocol errors`() = runTest {
        handshake()
        port.outcome = McpAppToolOutcome("boom", null, isError = true)

        val response = parse(router.handle(request(2, "tools/call", toolCallParams())).single())

        assertNull(response["error"])
        assertTrue(response["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `tools call rejects invalid params without calling the port`() = runTest {
        handshake()

        val missingName = parse(router.handle(request(2, "tools/call", toolCallParams(name = null))).single())
        val blankName = parse(router.handle(request(3, "tools/call", toolCallParams(name = "  "))).single())
        val arrayArgs = parse(
            router.handle(request(4, "tools/call", toolCallParams(arguments = buildJsonArray {}))).single(),
        )
        val numericName = parse(
            router.handle(
                request(5, "tools/call", buildJsonObject { put("name", 5) }),
            ).single(),
        )

        assertEquals(-32602, missingName.errorCode())
        assertEquals(-32602, blankName.errorCode())
        assertEquals(-32602, arrayArgs.errorCode())
        assertEquals(-32602, numericName.errorCode())
        assertTrue(port.toolCalls.isEmpty())
    }

    @Test
    fun `missing arguments default to an empty object`() = runTest {
        handshake()

        router.handle(request(2, "tools/call", toolCallParams(arguments = null)))

        assertEquals(listOf("refresh" to JsonObject(emptyMap())), port.toolCalls)
    }

    @Test
    fun `a throwing port becomes a bounded protocol error`() = runTest {
        handshake()
        port.failure = IllegalStateException("x".repeat(500))

        val response = parse(router.handle(request(2, "tools/call", toolCallParams())).single())

        assertEquals(-32000, response.errorCode())
        assertEquals(200, response.errorMessage()?.length)
    }

    @Test
    fun `in flight tool calls are capped and the slot is released afterwards`() = runTest {
        handshake()
        val gate = CompletableDeferred<Unit>()
        port.gate = gate
        val pending = List(McpAppBridgeRouter.MAX_IN_FLIGHT_TOOL_CALLS) { index ->
            async { router.handle(request(100 + index, "tools/call", toolCallParams())) }
        }
        runCurrent()

        val overflow = parse(router.handle(request(200, "tools/call", toolCallParams())).single())
        assertEquals(-32000, overflow.errorCode())
        assertEquals("Too many concurrent tool calls", overflow.errorMessage())

        gate.complete(Unit)
        val finished = pending.awaitAll().map { parse(it.single()) }
        assertTrue(finished.all { it["result"] != null })

        port.gate = null
        val afterwards = parse(router.handle(request(201, "tools/call", toolCallParams())).single())
        assertNotNull(afterwards["result"])
    }

    @Test
    fun `open link accepts only https and honors the user decision`() = runTest {
        handshake()
        fun linkParams(url: String) = buildJsonObject { put("url", url) }

        val ok = parse(router.handle(request(2, "ui/open-link", linkParams("https://example.com/a"))).single())
        assertNotNull(ok["result"])
        assertEquals(listOf("https://example.com/a"), port.links)

        port.linkDecision = false
        val denied = parse(router.handle(request(3, "ui/open-link", linkParams("https://example.com/b"))).single())
        assertEquals(-32000, denied.errorCode())
        assertEquals("Link opening denied by user", denied.errorMessage())

        val rejected = listOf(
            "http://example.com",
            "javascript:alert(1)",
            "intent://scan/#Intent;scheme=zxing;end",
            "https://user:pw@example.com",
            "https://",
            "not a url",
            "https://example.com/" + "a".repeat(2100),
        ).mapIndexed { index, url ->
            parse(router.handle(request(10 + index, "ui/open-link", linkParams(url))).single())
        }
        assertTrue(rejected.all { it.errorCode() == -32000 && it.errorMessage() == "Invalid URL" })
        // Only the two https links above ever asked the user.
        assertEquals(2, port.links.size)
    }

    @Test
    fun `message context and resource methods are refused in v1`() = runTest {
        handshake()
        val message = parse(
            router.handle(
                request(2, "ui/message", buildJsonObject { put("role", "user") }),
            ).single(),
        )
        val context = parse(router.handle(request(3, "ui/update-model-context")).single())
        val resources = parse(router.handle(request(4, "resources/read")).single())
        val unknown = parse(router.handle(request(5, "sampling/createMessage")).single())

        assertEquals(-32000, message.errorCode())
        assertEquals(-32000, context.errorCode())
        assertEquals(-32601, resources.errorCode())
        assertEquals(-32601, unknown.errorCode())
        assertTrue(port.toolCalls.isEmpty())
    }

    @Test
    fun `display mode requests always resolve to inline`() = runTest {
        handshake()

        val response = parse(
            router.handle(
                request(2, "ui/request-display-mode", buildJsonObject { put("mode", "fullscreen") }),
            ).single(),
        )

        assertEquals("inline", response["result"]!!.jsonObject["mode"]?.jsonPrimitive?.content)
    }

    @Test
    fun `size changes are clamped and ignored before the handshake or when not numeric`() = runTest {
        router.handle(notification("ui/notifications/size-changed", buildJsonObject { put("height", 300) }))
        assertTrue(port.heights.isEmpty())

        handshake()
        router.handle(notification("ui/notifications/size-changed", buildJsonObject { put("height", 300.9) }))
        router.handle(notification("ui/notifications/size-changed", buildJsonObject { put("height", -50) }))
        router.handle(notification("ui/notifications/size-changed", buildJsonObject { put("height", 1.0e12) }))
        router.handle(notification("ui/notifications/size-changed", buildJsonObject { put("height", "tall") }))
        router.handle(notification("ui/notifications/size-changed", buildJsonObject { put("width", 100) }))

        assertEquals(listOf(300, 0, 100_000), port.heights)
    }

    @Test
    fun `log notifications are bounded and only accepted after the handshake`() = runTest {
        val params = buildJsonObject {
            put("level", "info")
            put("data", "y".repeat(2000))
        }
        router.handle(notification("notifications/message", params))
        assertTrue(port.logs.isEmpty())

        handshake()
        router.handle(notification("notifications/message", params))

        assertEquals(1, port.logs.size)
        assertTrue(port.logs.single().length <= 500)
        assertTrue(port.logs.single().startsWith("info"))
    }

    @Test
    fun `malformed oversize and response messages are dropped silently`() = runTest {
        assertTrue(router.handle("not json").isEmpty())
        assertTrue(router.handle("[1,2,3]").isEmpty())
        assertTrue(router.handle("""{"jsonrpc":"2.0","id":1,"result":{}}""").isEmpty())
        assertTrue(
            router.handle("""{"jsonrpc":"2.0","method":"ping","id":1,"pad":"${"a".repeat(McpAppBridgeRouter.MAX_MESSAGE_CHARS)}"}""")
                .isEmpty(),
        )
        // A request whose id is null is treated as a notification and never answered.
        assertTrue(router.handle("""{"jsonrpc":"2.0","id":null,"method":"ping"}""").isEmpty())
    }

    @Test
    fun `teardown request exists only after the view is ready and carries a unique id`() = runTest {
        assertNull(router.teardownMessage())
        router.handle(request(1, "ui/initialize"))
        assertNull(router.teardownMessage())
        router.handle(notification("ui/notifications/initialized"))

        val first = parse(router.teardownMessage("closing")!!)
        val second = parse(router.teardownMessage()!!)

        assertEquals("ui/resource-teardown", first["method"]?.jsonPrimitive?.content)
        assertEquals("closing", first["params"]!!.jsonObject["reason"]?.jsonPrimitive?.content)
        assertEquals("host-teardown", second["params"]!!.jsonObject["reason"]?.jsonPrimitive?.content)
        assertTrue(first["id"]!!.jsonPrimitive.int != second["id"]!!.jsonPrimitive.int)
    }

    @Test
    fun `safe https url accepts plain https and rejects everything else`() {
        assertEquals("https://example.com/a?b=1", safeHttpsUrl("  https://example.com/a?b=1  "))
        assertNull(safeHttpsUrl("http://example.com"))
        assertNull(safeHttpsUrl("ftp://example.com"))
        assertNull(safeHttpsUrl("https://user@example.com"))
        assertNull(safeHttpsUrl("https:///path"))
        assertNull(safeHttpsUrl("//example.com"))
        assertNull(safeHttpsUrl("https://example.com/" + "a".repeat(2100)))
    }

    @Test
    fun `call tool result keeps only a structured object and always sets isError`() {
        val withObject = buildCallToolResult("t", """{"a":1}""", isError = false)
        assertEquals(buildJsonObject { put("a", 1) }, withObject["structuredContent"])
        assertFalse(withObject["isError"]!!.jsonPrimitive.boolean)

        assertNull(buildCallToolResult("t", """[1,2]""", false)["structuredContent"])
        assertNull(buildCallToolResult("t", "not json", false)["structuredContent"])
        assertNull(buildCallToolResult("t", null, false)["structuredContent"])
        assertTrue(buildCallToolResult("t", null, true)["isError"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `tool input is the persisted object or an empty object`() {
        assertEquals(buildJsonObject { put("q", "x") }, buildMcpAppToolInput("""{"q":"x"}"""))
        assertEquals(JsonObject(emptyMap()), buildMcpAppToolInput(null))
        assertEquals(JsonObject(emptyMap()), buildMcpAppToolInput("[1]"))
        assertEquals(JsonObject(emptyMap()), buildMcpAppToolInput("{broken"))
    }

    @Test
    fun `host context describes an inline mobile touch host`() {
        val context = buildMcpAppHostContext(
            isDark = true,
            localeTag = "es-MX",
            timeZoneId = "America/Mexico_City",
            maxHeightCssPx = 800,
        )

        assertEquals("dark", context["theme"]?.jsonPrimitive?.content)
        assertEquals("inline", context["displayMode"]?.jsonPrimitive?.content)
        assertEquals(
            buildJsonArray { add(JsonPrimitive("inline")) },
            context["availableDisplayModes"],
        )
        assertEquals(800, context["containerDimensions"]!!.jsonObject["maxHeight"]?.jsonPrimitive?.int)
        assertEquals("es-MX", context["locale"]?.jsonPrimitive?.content)
        assertEquals("America/Mexico_City", context["timeZone"]?.jsonPrimitive?.content)
        assertEquals("mobile", context["platform"]?.jsonPrimitive?.content)
        assertEquals("light", buildMcpAppHostContext(false, "en", "UTC", 100)["theme"]?.jsonPrimitive?.content)
    }
}
