package com.newoether.agora.mcp.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.security.MessageDigest

/**
 * Pure sandbox policy for MCP App documents (SEP-1865, 2026-01-26).
 *
 * Owns three decisions and nothing else: the synthetic per-document origin, the CSP built from the
 * server-declared `_meta.ui.csp` domains, and which sub-requests the WebView may let through.
 * Domains are validated as bare `https`/`wss` origins before they reach a header, so a server can
 * never inject a directive or widen the policy beyond what it declares.
 */
internal object McpAppSandbox {
    const val ORIGIN_SUFFIX = ".mcp-app.invalid"
    private const val MAX_DOMAINS_PER_DIRECTIVE = 16

    /** v1 never grants camera, microphone, geolocation or clipboard-write to a view. */
    const val PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), clipboard-write=()"

    private val DOMAIN_PATTERN =
        Regex("""^(https|wss)://(\*\.)?[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:\d{1,5})?$""")

    /** Dedicated opaque-looking origin per (server, document); no two documents share storage. */
    fun origin(serverId: String, resourceUri: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$serverId\n$resourceUri".toByteArray(Charsets.UTF_8))
        return "https://" + digest.take(16).joinToString("") { "%02x".format(it) } + ORIGIN_SUFFIX
    }

    fun pageUrl(origin: String): String = "$origin/"

    /** Declared and syntactically valid domains for one `_meta.ui.csp` key. */
    fun domains(uiMeta: JsonObject?, key: String): List<String> {
        val csp = uiMeta?.get("csp") as? JsonObject
        return (csp?.get(key) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter(DOMAIN_PATTERN::matches)
            ?.distinct()
            ?.take(MAX_DOMAINS_PER_DIRECTIVE)
            .orEmpty()
    }

    fun buildCsp(uiMeta: JsonObject?): String {
        val connect = domains(uiMeta, "connectDomains")
        val resources = domains(uiMeta, "resourceDomains")
        val frames = domains(uiMeta, "frameDomains")
        val base = domains(uiMeta, "baseUriDomains")
        fun sources(declared: List<String>, vararg fixed: String): String =
            (fixed.toList() + declared).joinToString(" ")
        return listOf(
            "default-src 'none'",
            "script-src ${sources(resources, "'self'", "'unsafe-inline'")}",
            "style-src ${sources(resources, "'self'", "'unsafe-inline'")}",
            "connect-src ${if (connect.isEmpty()) "'none'" else sources(connect)}",
            "img-src ${sources(resources, "'self'", "data:")}",
            "font-src ${sources(resources, "'self'", "data:")}",
            "media-src ${sources(resources, "'self'", "data:")}",
            "frame-src ${if (frames.isEmpty()) "'none'" else sources(frames)}",
            "object-src 'none'",
            "base-uri ${if (base.isEmpty()) "'self'" else sources(base)}",
            "form-action 'none'",
        ).joinToString("; ")
    }

    fun pageHeaders(uiMeta: JsonObject?): Map<String, String> = mapOf(
        "Content-Security-Policy" to buildCsp(uiMeta),
        "Permissions-Policy" to PERMISSIONS_POLICY,
        "Cache-Control" to "no-store",
        "X-Content-Type-Options" to "nosniff",
        "Referrer-Policy" to "no-referrer",
    )

    /**
     * Second line of defense behind the CSP: the WebView only lets through the document itself and
     * requests to an origin the server declared. Everything else (including other paths on the
     * synthetic origin, `file:`, `content:`, `http:`) is blocked.
     */
    fun isRequestAllowed(url: String, origin: String, uiMeta: JsonObject?): Boolean {
        if (url == pageUrl(origin)) return true
        if (url.startsWith("blob:$origin/")) return true
        // Inline images/fonts/media are common; the CSP restricts `data:` to those directives only.
        // Checked before URI parsing because data URIs routinely contain characters URI() rejects.
        if (url.startsWith("data:", ignoreCase = true)) return true
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https" && scheme != "wss") return false
        val host = uri.host?.lowercase() ?: return false
        val declared = listOf("connectDomains", "resourceDomains", "frameDomains", "baseUriDomains")
            .flatMap { domains(uiMeta, it) }
        return declared.any { matchesDeclaredOrigin(it, scheme, host, uri.port) }
    }

    private fun matchesDeclaredOrigin(pattern: String, scheme: String, host: String, port: Int): Boolean {
        val wildcard = pattern.contains("://*.")
        val declared = runCatching { URI(pattern.replace("://*.", "://")) }.getOrNull() ?: return false
        val declaredScheme = declared.scheme?.lowercase() ?: return false
        val declaredHost = declared.host?.lowercase() ?: return false
        val schemeOk = scheme == declaredScheme || (declaredScheme == "https" && scheme == "wss")
        val hostOk = if (wildcard) host.endsWith(".$declaredHost") else host == declaredHost
        val portOk = if (declared.port == -1) port == -1 else port == declared.port
        return schemeOk && hostOk && portOk
    }
}
