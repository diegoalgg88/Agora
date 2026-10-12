package com.newoether.agora.mcp.oauth

import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * Parsed `WWW-Authenticate: Bearer ...` challenge of an MCP resource server (RFC 6750 / RFC 9728).
 * Carries only what the OAuth flow needs; unknown parameters are dropped.
 */
internal data class McpAuthChallenge(
    val resourceMetadataUrl: String? = null,
    val scope: String? = null,
    val error: String? = null,
) {
    val insufficientScope: Boolean
        get() = error == "insufficient_scope"
}

/**
 * The server rejected the request and only an interactive OAuth sign-in (or a re-sign-in with
 * more scope) can fix it. The registry maps this to a snapshot with `authRequired = true` and
 * stops its backoff loop: retrying without user action can never succeed.
 */
internal class McpAuthRequiredException(
    val challenge: McpAuthChallenge,
    message: String = "Sign-in required",
) : IOException(message)

/** OAuth endpoint failure. [error] is the RFC 6749 error code when the server sent one. */
internal class McpOAuthException(
    val error: String?,
    message: String,
    val httpCode: Int = 0,
) : IOException(message) {
    val isInvalidGrant: Boolean
        get() = error == "invalid_grant"
}

/**
 * Source of the `Authorization` header for one MCP server.
 *
 * Transports call [authorizationHeader] before every request and, on a 401/403 challenge, call
 * [onRejected] exactly once; a `true` result means the credentials changed and one retry is
 * worthwhile. Implementations own token refresh and must serialize it per server.
 */
internal interface McpAuthProvider {
    /** `Bearer <token>` for the next request, refreshing an expired token; null when signed out. */
    suspend fun authorizationHeader(): String?

    /** [sentHeader] is the header that was rejected (null when the request was anonymous). */
    suspend fun onRejected(challenge: McpAuthChallenge, sentHeader: String?): Boolean
}

/** Per-server OAuth credentials and the client registration they belong to. */
@Serializable
internal data class McpOAuthRecord(
    /** Canonical MCP server URL the token is audience-bound to (RFC 8707). */
    val resource: String,
    val issuer: String,
    val tokenEndpoint: String,
    val clientId: String,
    val clientSecret: String = "",
    /** `none`, `client_secret_post` or `client_secret_basic`. */
    val authMethod: String = AUTH_METHOD_NONE,
    /** True when [clientId] came from dynamic client registration rather than user settings. */
    val registered: Boolean = false,
    val redirectUri: String,
    val accessToken: String = "",
    val refreshToken: String = "",
    /** Epoch millis; 0 means the server did not state a lifetime. */
    val expiresAtMs: Long = 0L,
    val scope: String = "",
) {
    val hasCredentials: Boolean
        get() = accessToken.isNotBlank() || refreshToken.isNotBlank()

    fun isExpired(nowMs: Long, skewMs: Long = McpOAuthDefaults.REFRESH_SKEW_MS): Boolean =
        expiresAtMs != 0L && nowMs + skewMs >= expiresAtMs

    fun signedOut(): McpOAuthRecord = copy(accessToken = "", refreshToken = "", expiresAtMs = 0L)

    companion object {
        const val AUTH_METHOD_NONE = "none"
        const val AUTH_METHOD_POST = "client_secret_post"
        const val AUTH_METHOD_BASIC = "client_secret_basic"
    }
}

/** An authorization request that left the app and has not come back yet. Single-use. */
@Serializable
internal data class McpOAuthPending(
    val serverId: String,
    val resource: String,
    val issuer: String,
    val tokenEndpoint: String,
    val clientId: String,
    val clientSecret: String = "",
    val authMethod: String = McpOAuthRecord.AUTH_METHOD_NONE,
    val registered: Boolean = false,
    val redirectUri: String,
    val codeVerifier: String,
    val scope: String = "",
    val createdAtMs: Long,
    /** RFC 9207: the AS announced it returns `iss`, so a response without it is rejected. */
    val issuerParameterRequired: Boolean = false,
)

/** The whole OAuth secret state, one encrypted DataStore value. Never exported. */
@Serializable
internal data class McpOAuthState(
    val records: Map<String, McpOAuthRecord> = emptyMap(),
    /** Keyed by the OAuth `state` parameter. */
    val pending: Map<String, McpOAuthPending> = emptyMap(),
)

internal object McpOAuthDefaults {
    /** Private-use scheme redirect (RFC 8252 §7.1) handled by `McpOAuthCallbackActivity`. */
    const val REDIRECT_URI = "com.newoether.agora:/oauth/mcp"
    const val CLIENT_NAME = "Agora"
    const val PENDING_TTL_MS = 10L * 60L * 1_000L
    const val REFRESH_SKEW_MS = 60_000L
}

/** Authorization server endpoints, from RFC 8414 / OIDC discovery or legacy defaults. */
internal data class McpAuthServerMetadata(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String? = null,
    /** Null = the server did not publish the list. */
    val codeChallengeMethods: List<String>? = null,
    val tokenEndpointAuthMethods: List<String>? = null,
    val scopesSupported: List<String> = emptyList(),
    val issuerParameterSupported: Boolean = false,
    /** Built from `/authorize`, `/token`, `/register` because no metadata document exists. */
    val legacyDefaults: Boolean = false,
) {
    /** MCP requires verifying PKCE support; defaults for metadata-less servers are exempt. */
    val supportsS256: Boolean
        get() = legacyDefaults || codeChallengeMethods?.contains("S256") == true
}

internal data class McpProtectedResourceMetadata(
    val resource: String?,
    val authorizationServers: List<String>,
    val scopesSupported: List<String>,
)

internal data class McpTokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long?,
    val scope: String?,
) {
    fun expiresAtMs(nowMs: Long): Long = expiresInSeconds?.let { nowMs + it * 1_000L } ?: 0L
}

internal data class McpClientRegistration(
    val clientId: String,
    val clientSecret: String,
    val authMethod: String,
)
