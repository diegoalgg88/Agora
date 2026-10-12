package com.newoether.agora.mcp.oauth

import com.newoether.agora.api.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/*
 * Pure OAuth 2.1 / MCP-authorization helpers: no Android, no I/O. Everything that decides what is
 * sent to, or trusted from, an authorization server is here so it can be unit-tested.
 */

private val BEARER_SCHEME = Regex("(?i)(?:^|[\\s,])Bearer(?:\\s|$)")

/** Extracts the Bearer challenge from the values of every `WWW-Authenticate` header. */
internal fun parseBearerChallenge(headerValues: List<String>): McpAuthChallenge? {
    for (raw in headerValues) {
        val match = BEARER_SCHEME.find(raw) ?: continue
        // The match consumed the scheme and at most one trailing separator; the rest is params.
        val params = parseAuthParams(raw.substring(match.range.last + 1))
        return McpAuthChallenge(
            resourceMetadataUrl = params["resource_metadata"]?.takeIf(String::isNotBlank),
            scope = params["scope"]?.takeIf(String::isNotBlank),
            error = params["error"]?.takeIf(String::isNotBlank),
        )
    }
    return null
}

private fun parseAuthParams(input: String): Map<String, String> {
    val out = linkedMapOf<String, String>()
    var i = 0
    val n = input.length
    while (i < n) {
        while (i < n && (input[i].isWhitespace() || input[i] == ',')) i++
        val keyStart = i
        while (i < n && input[i] != '=' && input[i] != ',' && !input[i].isWhitespace()) i++
        val key = input.substring(keyStart, i).lowercase()
        while (i < n && input[i].isWhitespace()) i++
        if (i >= n || input[i] != '=') continue
        i++
        while (i < n && input[i].isWhitespace()) i++
        val value = StringBuilder()
        if (i < n && input[i] == '"') {
            i++
            while (i < n && input[i] != '"') {
                if (input[i] == '\\' && i + 1 < n) i++
                value.append(input[i])
                i++
            }
            i++
        } else {
            while (i < n && input[i] != ',' && !input[i].isWhitespace()) {
                value.append(input[i])
                i++
            }
        }
        if (key.isNotEmpty()) out.putIfAbsent(key, value.toString())
    }
    return out
}

/** OAuth endpoints must be https; plain http is accepted only for local/LAN hosts (dev servers). */
internal fun isAllowedOAuthUrl(url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.fragment != null) {
        return false
    }
    return parsed.isHttps || HttpClient.isLocalHost(parsed.host)
}

/**
 * Canonical resource identifier (RFC 8707 / MCP): lowercase scheme and host, default port
 * dropped, no query, fragment or userinfo, no trailing slash. Query parameters are dropped on
 * purpose — they may carry API keys and are not part of the resource's identity.
 */
internal fun canonicalMcpResource(url: String): String? {
    val parsed = url.trim().toHttpUrlOrNull() ?: return null
    val path = parsed.encodedPath.trimEnd('/')
    return buildString {
        append(parsed.scheme).append("://").append(
            if (parsed.host.contains(':')) "[${parsed.host}]" else parsed.host,
        )
        if (parsed.port != HttpUrl.defaultPort(parsed.scheme)) append(':').append(parsed.port)
        append(path)
    }
}

/**
 * RFC 9728 §3.3: the advertised `resource` must identify the server we connected to. Servers
 * commonly advertise their origin while serving MCP below a path, so a parent is accepted.
 */
internal fun protectedResourceMatches(advertised: String, canonical: String): Boolean {
    val normalized = canonicalMcpResource(advertised) ?: return false
    return canonical == normalized || canonical.startsWith("$normalized/")
}

/** Where to look for Protected Resource Metadata when the 401 did not say (RFC 9728 §3.1). */
internal fun protectedResourceMetadataCandidates(
    resource: String,
    challenge: McpAuthChallenge?,
): List<String> {
    val parsed = resource.toHttpUrlOrNull() ?: return emptyList()
    val origin = "${parsed.scheme}://${authority(parsed)}"
    val path = parsed.encodedPath.trimEnd('/')
    return buildList {
        challenge?.resourceMetadataUrl?.let(::add)
        if (path.isNotEmpty()) add("$origin/.well-known/oauth-protected-resource$path")
        add("$origin/.well-known/oauth-protected-resource")
    }.distinct()
}

/** RFC 8414 §3.1 plus OIDC discovery order mandated by the MCP authorization spec. */
internal fun authorizationServerMetadataCandidates(issuer: String): List<String> {
    val parsed = issuer.toHttpUrlOrNull() ?: return emptyList()
    val origin = "${parsed.scheme}://${authority(parsed)}"
    val path = parsed.encodedPath.trimEnd('/')
    return if (path.isNotEmpty()) {
        listOf(
            "$origin/.well-known/oauth-authorization-server$path",
            "$origin/.well-known/openid-configuration$path",
            "$origin$path/.well-known/openid-configuration",
        )
    } else {
        listOf(
            "$origin/.well-known/oauth-authorization-server",
            "$origin/.well-known/openid-configuration",
        )
    }
}

internal fun originOf(url: String): String? =
    url.toHttpUrlOrNull()?.let { "${it.scheme}://${authority(it)}" }

private fun authority(url: HttpUrl): String {
    val host = if (url.host.contains(':')) "[${url.host}]" else url.host
    return if (url.port == HttpUrl.defaultPort(url.scheme)) host else "$host:${url.port}"
}

internal fun sameIssuer(a: String, b: String): Boolean = a.trimEnd('/') == b.trimEnd('/')

/** Metadata defaults for servers that predate discovery (MCP 2025-03-26 compatibility). */
internal fun legacyAuthServerDefaults(origin: String): McpAuthServerMetadata =
    McpAuthServerMetadata(
        issuer = origin,
        authorizationEndpoint = "$origin/authorize",
        tokenEndpoint = "$origin/token",
        registrationEndpoint = "$origin/register",
        legacyDefaults = true,
    )

internal fun parseAuthServerMetadata(
    obj: JsonObject,
    expectedIssuer: String,
): McpAuthServerMetadata? {
    val issuer = obj.string("issuer") ?: return null
    // RFC 8414 §3.3: reject a document that claims a different issuer (mix-up protection).
    if (!sameIssuer(issuer, expectedIssuer)) return null
    val authorization = obj.string("authorization_endpoint")?.takeIf(::isAllowedOAuthUrl) ?: return null
    val token = obj.string("token_endpoint")?.takeIf(::isAllowedOAuthUrl) ?: return null
    return McpAuthServerMetadata(
        issuer = issuer,
        authorizationEndpoint = authorization,
        tokenEndpoint = token,
        registrationEndpoint = obj.string("registration_endpoint")?.takeIf(::isAllowedOAuthUrl),
        codeChallengeMethods = obj.stringList("code_challenge_methods_supported"),
        tokenEndpointAuthMethods = obj.stringList("token_endpoint_auth_methods_supported"),
        scopesSupported = obj.stringList("scopes_supported").orEmpty(),
        issuerParameterSupported =
            (obj["authorization_response_iss_parameter_supported"] as? JsonPrimitive)
                ?.contentOrNull == "true",
    )
}

internal fun parseProtectedResourceMetadata(obj: JsonObject): McpProtectedResourceMetadata =
    McpProtectedResourceMetadata(
        resource = obj.string("resource"),
        authorizationServers = obj.stringList("authorization_servers").orEmpty()
            .filter(::isAllowedOAuthUrl),
        scopesSupported = obj.stringList("scopes_supported").orEmpty(),
    )

/** `none` when the server allows public clients, else the strongest secret-based method it lists. */
internal fun chooseRegistrationAuthMethod(supported: List<String>?): String = when {
    supported == null || "none" in supported -> McpOAuthRecord.AUTH_METHOD_NONE
    "client_secret_post" in supported -> McpOAuthRecord.AUTH_METHOD_POST
    "client_secret_basic" in supported -> McpOAuthRecord.AUTH_METHOD_BASIC
    else -> McpOAuthRecord.AUTH_METHOD_NONE
}

private val secureRandom = SecureRandom()
private val base64Url = Base64.getUrlEncoder().withoutPadding()

internal fun randomUrlToken(byteCount: Int): String =
    ByteArray(byteCount).also(secureRandom::nextBytes).let(base64Url::encodeToString)

/** RFC 7636: 48 random bytes encode to a 64-character verifier (limit 43..128). */
internal fun generateCodeVerifier(): String = randomUrlToken(48)

internal fun codeChallengeS256(verifier: String): String =
    base64Url.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
    )

internal fun buildAuthorizationUrl(
    authorizationEndpoint: String,
    clientId: String,
    redirectUri: String,
    state: String,
    codeChallenge: String,
    resource: String,
    scope: String?,
): String {
    val base = requireNotNull(authorizationEndpoint.toHttpUrlOrNull()) {
        "Invalid authorization endpoint"
    }
    return base.newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", clientId)
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("state", state)
        .addQueryParameter("code_challenge", codeChallenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("resource", resource)
        .apply { if (!scope.isNullOrBlank()) addQueryParameter("scope", scope) }
        .build()
        .toString()
}

/**
 * Parses the redirect that returned to the app. Only [expectedRedirect] (scheme and path) is
 * accepted; anything else yields null so a foreign link can never reach the code exchange.
 */
internal fun parseOAuthRedirect(redirect: String, expectedRedirect: String): Map<String, String>? {
    val expected = runCatching { java.net.URI(expectedRedirect) }.getOrNull() ?: return null
    val actual = runCatching { java.net.URI(redirect) }.getOrNull() ?: return null
    if (!actual.scheme.equals(expected.scheme, ignoreCase = true) || actual.rawPath != expected.rawPath) {
        return null
    }
    val query = actual.rawQuery ?: return emptyMap()
    return query.split('&').filter(String::isNotEmpty).associate { pair ->
        val separator = pair.indexOf('=')
        val name = if (separator < 0) pair else pair.substring(0, separator)
        val value = if (separator < 0) "" else pair.substring(separator + 1)
        URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
    }
}

/** Space-delimited scope union that keeps first-seen order. */
internal fun mergeScopes(vararg scopes: String?): String? =
    scopes.filterNotNull()
        .flatMap { it.split(' ', '\t').filter(String::isNotEmpty) }
        .distinct()
        .joinToString(" ")
        .takeIf(String::isNotBlank)

internal fun formEncode(params: Map<String, String>): String =
    params.entries.joinToString("&") { (name, value) ->
        URLEncoder.encode(name, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
    }

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

private fun JsonObject.stringList(key: String): List<String>? =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

internal fun JsonElement.asJsonObjectOrNull(): JsonObject? = this as? JsonObject
