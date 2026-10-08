package com.newoether.agora.mcp.ui

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** MCP Apps spec revision this host implements (`ui/initialize` protocolVersion). */
internal const val MCP_APPS_PROTOCOL_VERSION = "2026-01-26"

internal data class McpAppToolOutcome(
    val text: String,
    val structuredContent: String?,
    val isError: Boolean,
)

/**
 * Everything the router may ask of the host. The implementation owns the server binding: it only
 * calls app-visible tools of the server that owns the view and decides whether a link opens.
 */
internal interface McpAppHostPort {
    suspend fun callTool(name: String, arguments: JsonObject): McpAppToolOutcome
    suspend fun openLink(url: String): Boolean
    fun onSizeChanged(heightCssPx: Int)
    fun onLog(message: String)
}

/**
 * Pure JSON-RPC 2.0 router for one MCP App view. It has no Android, Room, Provider or UI
 * dependency: it maps one raw view message to zero or more raw host messages.
 *
 * Handshake order is enforced as the spec requires: the host never sends anything to the view
 * before `ui/notifications/initialized`, and `tool-input` precedes `tool-result`.
 * v1 scope: `tools/call`, `ui/open-link`, `ui/request-display-mode` (inline only), `ping`,
 * `ui/notifications/size-changed` and `notifications/message`. `ui/message`,
 * `ui/update-model-context` and `resources/read` are refused so a view can never write into the
 * conversation or model context.
 */
internal class McpAppBridgeRouter(
    private val port: McpAppHostPort,
    private val hostContext: JsonObject,
    private val hostVersion: String,
    private val toolInput: JsonObject,
    private val toolResult: JsonObject,
) {
    companion object {
        const val MAX_MESSAGE_CHARS = 1_048_576
        const val MAX_IN_FLIGHT_TOOL_CALLS = 4
        private const val MAX_LOG_CHARS = 500
        private const val MAX_HEIGHT_CSS_PX = 100_000.0
    }

    private val initializeAnswered = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private val toolCallsInFlight = AtomicInteger(0)
    private val nextRequestId = AtomicLong(1)

    val isReady: Boolean
        get() = ready.get()

    suspend fun handle(raw: String): List<String> {
        if (raw.length > MAX_MESSAGE_CHARS) return emptyList()
        val message = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return emptyList()
        // Messages without a method are responses to host requests (for example teardown).
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull ?: return emptyList()
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        val id = message["id"]?.takeIf { it is JsonPrimitive && it !is JsonNull }
        return if (id == null) handleNotification(method, params) else handleRequest(id, method, params)
    }

    /** Host-initiated teardown request; null until the view completed its handshake. */
    fun teardownMessage(reason: String = "host-teardown"): String? {
        if (!ready.get()) return null
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", nextRequestId.getAndIncrement())
            put("method", "ui/resource-teardown")
            put("params", buildJsonObject { put("reason", reason) })
        }.toString()
    }

    private fun handleNotification(method: String, params: JsonObject): List<String> = when (method) {
        "ui/notifications/initialized" ->
            if (initializeAnswered.get() && ready.compareAndSet(false, true)) {
                listOf(
                    notification(
                        "ui/notifications/tool-input",
                        buildJsonObject { put("arguments", toolInput) },
                    ),
                    notification("ui/notifications/tool-result", toolResult),
                )
            } else {
                emptyList()
            }
        "ui/notifications/size-changed" -> {
            if (ready.get()) {
                (params["height"] as? JsonPrimitive)?.doubleOrNull?.let { height ->
                    port.onSizeChanged(height.coerceIn(0.0, MAX_HEIGHT_CSS_PX).toInt())
                }
            }
            emptyList()
        }
        "notifications/message" -> {
            if (ready.get()) {
                val level = (params["level"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val data = params["data"]?.toString().orEmpty()
                port.onLog("$level $data".trim().take(MAX_LOG_CHARS))
            }
            emptyList()
        }
        else -> emptyList()
    }

    private suspend fun handleRequest(id: JsonElement, method: String, params: JsonObject): List<String> {
        if (method == "ping") return listOf(resultResponse(id, JsonObject(emptyMap())))
        if (method == "ui/initialize") {
            initializeAnswered.set(true)
            return listOf(resultResponse(id, initializeResult()))
        }
        if (!ready.get()) {
            return listOf(errorResponse(id, -32002, "View has not completed initialization"))
        }
        return listOf(
            when (method) {
                "tools/call" -> toolCall(id, params)
                "ui/open-link" -> openLink(id, params)
                "ui/request-display-mode" ->
                    resultResponse(id, buildJsonObject { put("mode", "inline") })
                "ui/message" -> errorResponse(id, -32000, "Message sending denied")
                "ui/update-model-context" -> errorResponse(id, -32000, "Context update denied")
                else -> errorResponse(id, -32601, "Method not found: $method")
            },
        )
    }

    private suspend fun toolCall(id: JsonElement, params: JsonObject): String {
        val name = (params["name"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf(String::isNotBlank)
            ?: return errorResponse(id, -32602, "Invalid params: name")
        val arguments = when (val value = params["arguments"]) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> value
            else -> return errorResponse(id, -32602, "Invalid params: arguments")
        }
        if (toolCallsInFlight.incrementAndGet() > MAX_IN_FLIGHT_TOOL_CALLS) {
            toolCallsInFlight.decrementAndGet()
            return errorResponse(id, -32000, "Too many concurrent tool calls")
        }
        return try {
            val outcome = port.callTool(name, arguments)
            resultResponse(id, buildCallToolResult(outcome.text, outcome.structuredContent, outcome.isError))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorResponse(id, -32000, e.message?.take(200) ?: "Tool call failed")
        } finally {
            toolCallsInFlight.decrementAndGet()
        }
    }

    private suspend fun openLink(id: JsonElement, params: JsonObject): String {
        val url = (params["url"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.let(::safeHttpsUrl)
            ?: return errorResponse(id, -32000, "Invalid URL")
        return if (port.openLink(url)) {
            resultResponse(id, JsonObject(emptyMap()))
        } else {
            errorResponse(id, -32000, "Link opening denied by user")
        }
    }

    private fun initializeResult(): JsonObject = buildJsonObject {
        put("protocolVersion", MCP_APPS_PROTOCOL_VERSION)
        put(
            "hostCapabilities",
            buildJsonObject {
                put("serverTools", JsonObject(emptyMap()))
                put("openLinks", JsonObject(emptyMap()))
                put("logging", JsonObject(emptyMap()))
            },
        )
        put(
            "hostInfo",
            buildJsonObject {
                put("name", "Agora")
                put("version", hostVersion)
            },
        )
        put("hostContext", hostContext)
    }

    private fun notification(method: String, params: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        put("params", params)
    }.toString()

    private fun resultResponse(id: JsonElement, result: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.toString()

    private fun errorResponse(id: JsonElement, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            },
        )
    }.toString()
}

/** Standard MCP `CallToolResult` for a view. Images are not forwarded in v1. */
internal fun buildCallToolResult(
    text: String,
    structuredContent: String?,
    isError: Boolean,
): JsonObject = buildJsonObject {
    put(
        "content",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
        },
    )
    val structured = structuredContent
        ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonObject }
    if (structured != null) put("structuredContent", structured)
    put("isError", isError)
}

/** The complete tool arguments for `ui/notifications/tool-input`; `{}` when absent or invalid. */
internal fun buildMcpAppToolInput(toolArgs: String?): JsonObject =
    (toolArgs?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject)
        ?: JsonObject(emptyMap())

internal fun buildMcpAppHostContext(
    isDark: Boolean,
    localeTag: String,
    timeZoneId: String,
    maxHeightCssPx: Int,
): JsonObject = buildJsonObject {
    put("theme", if (isDark) "dark" else "light")
    put("displayMode", "inline")
    put("availableDisplayModes", buildJsonArray { add(JsonPrimitive("inline")) })
    put("containerDimensions", buildJsonObject { put("maxHeight", maxHeightCssPx) })
    put("locale", localeTag)
    put("timeZone", timeZoneId)
    put("userAgent", "Agora")
    put("platform", "mobile")
    put(
        "deviceCapabilities",
        buildJsonObject {
            put("touch", true)
            put("hover", false)
        },
    )
}

/** `https` URLs only, no credentials, bounded length; anything else is refused. */
internal fun safeHttpsUrl(raw: String): String? {
    if (raw.length > 2048) return null
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
    return uri.toString()
}
