package com.newoether.agora.mcp

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.util.Base64

enum class McpConnectionStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    ERROR,
}

/** MCP Apps tool visibility audiences (`_meta.ui.visibility`). */
object McpUiVisibility {
    const val MODEL = "model"
    const val APP = "app"
    val DEFAULT: Set<String> = setOf(MODEL, APP)
}

data class McpRemoteTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    /** `ui://` resource declared by `_meta.ui.resourceUri`; null when the tool has no MCP App. */
    val uiResourceUri: String? = null,
    /** Audiences allowed to call this tool. Never empty. */
    val uiVisibility: Set<String> = McpUiVisibility.DEFAULT,
)

data class McpToolDescriptor(
    val publicName: String,
    val serverId: String,
    val serverName: String,
    val remote: McpRemoteTool,
    val enabled: Boolean = true,
) {
    val uiResourceUri: String?
        get() = remote.uiResourceUri

    /** Offered to the model as a callable tool. App-only tools are never model-visible. */
    val isModelVisible: Boolean
        get() = McpUiVisibility.MODEL in remote.uiVisibility

    /** Callable by an MCP App view hosted for this same server. */
    val isAppVisible: Boolean
        get() = McpUiVisibility.APP in remote.uiVisibility

    fun asToolDefinition(): ToolDefinition = ToolDefinition(
        function = ToolFunction(
            name = publicName,
            description = buildString {
                append(remote.description.ifBlank { "MCP tool ${remote.name}" })
                append("\n\nProvided by MCP server: ")
                append(serverName)
            },
            parameters = remote.inputSchema.toToolParameters(),
        ),
    )
}

/**
 * A resource advertised by `resources/list`. Resources are listed in settings so the user can see
 * what a server exposes (documentation, MCP App documents). They are display-only: nothing here is
 * offered to a model or read automatically.
 */
data class McpRemoteResource(
    val uri: String,
    /** Display name: the resource `title`, else its `name`, else its URI. */
    val name: String,
    val description: String? = null,
    val mimeType: String? = null,
) {
    val isMcpAppDocument: Boolean
        get() = uri.startsWith("ui://")
}

data class McpServerSnapshot(
    val serverId: String,
    val status: McpConnectionStatus = McpConnectionStatus.IDLE,
    val tools: List<McpToolDescriptor> = emptyList(),
    val error: String? = null,
    val lastSyncedAt: Long? = null,
    val resources: List<McpRemoteResource> = emptyList(),
)

data class McpImagePayload(
    val data: String,
    val mimeType: String,
)

data class McpCallPayload(
    val textParts: List<String>,
    val images: List<McpImagePayload>,
    val structuredContent: JsonElement?,
    val isError: Boolean,
)

internal fun publicMcpToolName(serverId: String, remoteName: String): String {
    val serverKey = serverId.filter(Char::isLetterOrDigit).take(10).ifBlank { "server" }
    val toolKey = remoteName
        .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
        .joinToString("")
        .trim('_')
        .ifBlank { "tool" }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(remoteName.toByteArray())
        .take(3)
        .joinToString("") { "%02x".format(it) }
    val prefix = "mcp_${serverKey}_"
    val suffix = "_$digest"
    return prefix + toolKey.take((64 - prefix.length - suffix.length).coerceAtLeast(1)) + suffix
}

private fun JsonObject.toToolParameters(): ToolParameters {
    val properties = (this["properties"] as? JsonObject)
        ?.entries
        ?.sortedBy { it.key }
        ?.associate { (name, schema) ->
            name to (schema as? JsonObject).toToolProperty()
        }
        .orEmpty()
    val required = (this["required"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
        .filter(properties::containsKey)
    return ToolParameters(
        type = (this["type"] as? JsonPrimitive)?.contentOrNull ?: "object",
        properties = properties,
        required = required,
    )
}

private fun JsonObject?.toToolProperty(): ToolProperty {
    if (this == null) return ToolProperty(type = "string", description = "")
    val declaredType = when (val type = this["type"]) {
        is JsonPrimitive -> type.contentOrNull
        is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .firstOrNull { it != "null" }
        else -> null
    }
    return ToolProperty(
        type = declaredType ?: inferSchemaType(this),
        description = (this["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        items = (this["items"] as? JsonObject)?.toToolProperty(),
    )
}

private fun inferSchemaType(schema: JsonObject): String = when {
    schema["properties"] is JsonObject -> "object"
    schema["items"] is JsonObject -> "array"
    else -> "string"
}

internal data class McpToolUiMeta(
    val resourceUri: String?,
    val visibility: Set<String>,
)

/**
 * Reads MCP Apps tool metadata. The nested `_meta.ui.resourceUri` wins over the deprecated flat
 * `_meta["ui/resourceUri"]`; only `ui://` URIs are accepted. Unknown visibility audiences are
 * dropped and an empty result falls back to the default (model + app).
 */
internal fun parseMcpToolUiMeta(meta: JsonObject?): McpToolUiMeta {
    val ui = meta?.get("ui")?.asObjectOrNull()
    val resourceUri = sequenceOf(
        (ui?.get("resourceUri") as? JsonPrimitive)?.contentOrNull,
        (meta?.get("ui/resourceUri") as? JsonPrimitive)?.contentOrNull,
    ).firstOrNull { it != null && it.startsWith("ui://") && it.length > "ui://".length }
    val visibility = (ui?.get("visibility") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.filter { it == McpUiVisibility.MODEL || it == McpUiVisibility.APP }
        ?.toSet()
        ?.takeIf(Set<String>::isNotEmpty)
        ?: McpUiVisibility.DEFAULT
    return McpToolUiMeta(resourceUri = resourceUri, visibility = visibility)
}

internal const val MCP_UI_MIME_TYPE = "text/html;profile=mcp-app"
internal const val MCP_UI_EXTENSION_ID = "io.modelcontextprotocol/ui"

/**
 * Client capabilities sent in `initialize`. MCP Apps support is advertised through the SEP-1724
 * `extensions` map; servers must keep a text fallback, and the UI falls back to the plain result
 * whenever a view cannot be rendered.
 */
internal fun mcpClientCapabilities(): JsonObject = buildJsonObject {
    put(
        "extensions",
        buildJsonObject {
            put(
                MCP_UI_EXTENSION_ID,
                buildJsonObject {
                    put("mimeTypes", buildJsonArray { add(JsonPrimitive(MCP_UI_MIME_TYPE)) })
                },
            )
        },
    )
}
internal const val MAX_MCP_UI_RESOURCE_BYTES = 2 * 1024 * 1024
private const val MAX_MCP_UI_RESOURCE_BASE64_CHARS = MAX_MCP_UI_RESOURCE_BYTES / 3 * 4 + 8

/**
 * Durable pointer from a completed tool result to its MCP App document. It carries no HTML and no
 * result payload: the view re-reads the document on demand and rebuilds the tool result from the
 * persisted segment.
 */
data class McpUiReference(
    val serverId: String,
    val resourceUri: String,
)

/** A validated MCP App HTML document. [uiMeta] is `_meta.ui` of the resource content (CSP etc.). */
data class McpUiResource(
    val uri: String,
    val html: String,
    val uiMeta: JsonObject?,
)

/**
 * Validates a `resources/read` result for [requestedUri]. Fails closed (null) on a missing entry,
 * a URI that differs from the request, a MIME type other than the MCP App profile, a blank or
 * oversize document, or an undecodable blob.
 */
internal fun parseMcpUiResourceContents(requestedUri: String, result: JsonObject): McpUiResource? {
    val item = (result["contents"] as? JsonArray)
        ?.asSequence()
        ?.mapNotNull { it.asObjectOrNull() }
        ?.firstOrNull { (it["uri"] as? JsonPrimitive)?.contentOrNull == requestedUri }
        ?: return null
    val mime = (item["mimeType"] as? JsonPrimitive)?.contentOrNull
        ?.filterNot(Char::isWhitespace)
        ?.lowercase()
    if (mime != MCP_UI_MIME_TYPE) return null
    val text = (item["text"] as? JsonPrimitive)?.contentOrNull
    val blob = (item["blob"] as? JsonPrimitive)?.contentOrNull
    val html = when {
        text != null -> text.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_MCP_UI_RESOURCE_BYTES }
        blob != null -> blob
            .takeIf { it.length <= MAX_MCP_UI_RESOURCE_BASE64_CHARS }
            ?.let { runCatching { Base64.getMimeDecoder().decode(it) }.getOrNull() }
            ?.takeIf { it.size <= MAX_MCP_UI_RESOURCE_BYTES }
            ?.toString(Charsets.UTF_8)
        else -> null
    }?.takeIf(String::isNotBlank) ?: return null
    val uiMeta = item["_meta"]?.asObjectOrNull()?.get("ui")?.asObjectOrNull()
    return McpUiResource(uri = requestedUri, html = html, uiMeta = uiMeta)
}

/** Upper bound on resources kept per server; a catalogue can be far larger than is useful to show. */
internal const val MAX_MCP_RESOURCES = 500
private const val MAX_MCP_RESOURCE_URI_CHARS = 2048
private const val MAX_MCP_RESOURCE_TEXT_CHARS = 500
private const val MAX_MCP_RESOURCE_MIME_CHARS = 100

/**
 * Parses one `resources/list` page. Entries without a usable `uri` are skipped; `title` wins over
 * `name` as the display name. Returns null when the result carries no `resources` array.
 */
internal fun parseMcpResourcesPage(result: JsonObject): List<McpRemoteResource>? {
    val page = result["resources"] as? JsonArray ?: return null
    return page.mapNotNull { element ->
        val item = element.asObjectOrNull() ?: return@mapNotNull null
        fun text(key: String): String? =
            (item[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        val uri = text("uri") ?: return@mapNotNull null
        McpRemoteResource(
            uri = uri.take(MAX_MCP_RESOURCE_URI_CHARS),
            name = (text("title") ?: text("name") ?: uri).take(MAX_MCP_RESOURCE_TEXT_CHARS),
            description = text("description")?.take(MAX_MCP_RESOURCE_TEXT_CHARS),
            mimeType = text("mimeType")?.take(MAX_MCP_RESOURCE_MIME_CHARS),
        )
    }
}

/**
 * Whether `resources/list` should be called after `initialize`. A server that declares its
 * capabilities without `resources` is never asked, so it cannot fail a refresh; a result with no
 * capabilities object at all is treated as unknown and tried.
 */
internal fun mcpServerMayListResources(initializeResult: JsonObject): Boolean {
    val capabilities = initializeResult["capabilities"] as? JsonObject ?: return true
    return capabilities.containsKey("resources")
}

internal fun isToolsListChangedNotification(envelope: JsonObject): Boolean =
    (envelope["method"] as? JsonPrimitive)?.contentOrNull == "notifications/tools/list_changed"

internal fun JsonElement.asObjectOrNull(): JsonObject? =
    runCatching { jsonObject }.getOrNull()
