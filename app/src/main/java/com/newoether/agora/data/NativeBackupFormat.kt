package com.newoether.agora.data

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object NativeBackupFormat {
    const val CURRENT_VERSION = 4
    const val MIN_SUPPORTED_VERSION = 1

    const val MANIFEST_ENTRY = "manifest.json"
    const val CONVERSATIONS_ENTRY = "conversations.json"
    const val SETTINGS_ENTRY = "settings.json"
    const val LEGACY_EXTRA_SETTINGS_ENTRY = "extra_settings.json"
    const val SECRETS_ENTRY = "api_keys.json"
    const val SYSTEM_PROMPTS_ENTRY = "system_prompts.json"
    const val CUSTOM_FONT_ENTRY = "custom_font/font"

    const val IMAGE_MEDIA_PREFIX = "media/images/"
    const val VIDEO_MEDIA_PREFIX = "media/videos/"
    const val DRAFT_MEDIA_PREFIX = "media/drafts/"

    fun isSupported(version: Int): Boolean =
        version in MIN_SUPPORTED_VERSION..CURRENT_VERSION
}

@Serializable
internal data class ShellDeviceSecrets(
    val apiKey: String = "",
    val sshPassword: String = "",
)

@Serializable
internal data class NativeBackupSecrets(
    val apiKeys: List<ApiKeyEntry> = emptyList(),
    val activeApiKeyIds: Map<String, String> = emptyMap(),
    val webSearchApiKeys: Map<String, String> = emptyMap(),
    val proxyPassword: String = "",
    val shellDevices: Map<String, ShellDeviceSecrets> = emptyMap(),
    /** v1-v3 compatibility only. v4 always keys shell credentials by stable device ID. */
    val shellApiKeys: Map<String, String> = emptyMap(),
    val embeddingApiKeys: Map<String, String> = emptyMap(),
    val emailPasswords: Map<String, String> = emptyMap(),
    val mcpHeaders: Map<String, Map<String, String>> = emptyMap(),
    /** Original MCP server URLs whose credential query parameters were stripped from settings. */
    val mcpUrls: Map<String, String> = emptyMap(),
)

internal fun ShellDeviceConfig.withoutSecrets(): ShellDeviceConfig =
    copy(apiKey = "", sshPassword = "")

/**
 * Credential-bearing MCP URL query parameter names. "key" and "token" match only exactly (so
 * keywords/hotkeys stay); the longer markers match as substrings of the alphanumeric-normalized
 * name, covering forms like `tavilyApiKey`, `api_key`, `access-token`, and `client_secret`.
 */
internal fun isMcpUrlCredentialParameter(name: String): Boolean {
    val normalized = name.filter(Char::isLetterOrDigit).lowercase()
    return normalized == "key" || normalized == "token" ||
        normalized.contains("apikey") || normalized.contains("accesstoken") ||
        normalized.contains("apitoken") || normalized.contains("secret") ||
        normalized.contains("password") || normalized.contains("passwd") ||
        normalized.contains("credential")
}

/** True when [raw] is a parseable http(s) URL carrying at least one credential query parameter. */
internal fun hasMcpUrlCredentialParameters(raw: String): Boolean {
    val parsed = raw.toHttpUrlOrNull() ?: return false
    return parsed.queryParameterNames.any(::isMcpUrlCredentialParameter)
}

/**
 * Removes credential query parameters, preserving every other parameter in order. Unparseable or
 * clean URLs are returned unchanged — never destroyed, since a malformed URL carries no
 * reachable credential and config loss is the worse failure.
 */
internal fun sanitizeMcpUrlCredentials(raw: String): String {
    val parsed = raw.toHttpUrlOrNull() ?: return raw
    val offending = parsed.queryParameterNames.filter(::isMcpUrlCredentialParameter)
    if (offending.isEmpty()) return raw
    val builder = parsed.newBuilder()
    offending.forEach(builder::removeAllQueryParameters)
    return builder.build().toString()
}

internal fun McpServerConfig.withoutSecrets(): McpServerConfig {
    val sanitizedUrl = sanitizeMcpUrlCredentials(url)
    return if (sanitizedUrl == url) {
        copy(headers = emptyMap())
    } else {
        copy(headers = emptyMap(), url = sanitizedUrl)
    }
}

internal fun EmbeddingModelConfig.asPortableRemoteConfig(): EmbeddingModelConfig? =
    takeIf { it.type == EmbeddingModelType.REMOTE }
        ?.copy(remoteApiKey = "", localFilePath = "")
