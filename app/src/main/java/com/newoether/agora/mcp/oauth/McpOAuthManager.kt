package com.newoether.agora.mcp.oauth

import android.content.Context
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide owner of MCP OAuth (MCP authorization spec: RFC 9728 discovery, RFC 8414 / OIDC
 * metadata, RFC 7591 dynamic registration, authorization code + PKCE S256, RFC 8707 `resource`).
 *
 * Responsibilities, and nothing else:
 *  - hand every MCP runtime a [McpAuthProvider] that injects and refreshes the Bearer token;
 *  - run the interactive flow: [authorizationUrl] prepares and persists a single-use pending
 *    request, [complete] consumes the redirect and stores the tokens;
 *  - [signOut] / [forget] credential lifecycle.
 *
 * It never owns connection state: outcomes are reported through [onResult] and the registry
 * decides what to rebuild. Token refresh is serialized per server so a rotating refresh token is
 * never spent twice.
 */
internal class McpOAuthManager(
    context: Context,
    private val scope: CoroutineScope,
    private val serverConfig: (serverId: String) -> McpServerConfig?,
    /** `error == null` means credentials changed successfully; otherwise a user-facing message. */
    private val onResult: (serverId: String, error: String?) -> Unit,
    private val client: McpOAuthClient = McpOAuthClient(),
    private val store: McpOAuthStore = McpOAuthStore(context),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val challenges = ConcurrentHashMap<String, McpAuthChallenge>()
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()

    /** Auth provider for [config]'s runtime; null when its URL cannot be a resource identifier. */
    fun sessionFor(config: McpServerConfig): McpAuthProvider? =
        canonicalMcpResource(config.url)?.let { Session(config.id, it) }

    suspend fun isSignedIn(serverId: String): Boolean {
        val resource = serverConfig(serverId)?.url?.let(::canonicalMcpResource) ?: return false
        return store.read().records[serverId]
            ?.takeIf { it.resource == resource }
            ?.hasCredentials == true
    }

    /**
     * Discovers the authorization server, resolves the client, persists a single-use pending
     * request and returns the URL to open in the browser. Throws an [IOException] with a message
     * suitable for display on any discovery or registration failure.
     */
    suspend fun authorizationUrl(serverId: String): String {
        val config = serverConfig(serverId) ?: throw IOException("Unknown MCP server")
        val resource = canonicalMcpResource(config.url)
            ?: throw IOException("Invalid MCP server URL")
        val challenge = challenges[serverId]

        val protectedResource = client.discoverProtectedResource(resource, challenge)
        protectedResource?.resource?.let { advertised ->
            if (!protectedResourceMatches(advertised, resource)) {
                throw IOException("The server's OAuth metadata describes a different resource")
            }
        }
        val metadata = resolveAuthorizationServer(protectedResource, resource, challenge)
        val existing = store.read().records[serverId]
            ?.takeIf { it.resource == resource && sameIssuer(it.issuer, metadata.issuer) }
        val registration = resolveClient(config, metadata, existing)

        val requestedScope = config.oauthScope.trim().takeIf(String::isNotEmpty)
            ?: if (challenge?.insufficientScope == true) {
                mergeScopes(existing?.scope, challenge.scope)
            } else {
                challenge?.scope ?: protectedResource?.scopesSupported
                    ?.joinToString(" ")?.takeIf(String::isNotBlank)
            }

        val verifier = generateCodeVerifier()
        val state = randomUrlToken(32)
        val pending = McpOAuthPending(
            serverId = serverId,
            resource = resource,
            issuer = metadata.issuer,
            tokenEndpoint = metadata.tokenEndpoint,
            clientId = registration.clientId,
            clientSecret = registration.clientSecret,
            authMethod = registration.authMethod,
            registered = registration.registered,
            redirectUri = McpOAuthDefaults.REDIRECT_URI,
            codeVerifier = verifier,
            scope = requestedScope.orEmpty(),
            createdAtMs = clock(),
            issuerParameterRequired = metadata.issuerParameterSupported,
        )
        val now = clock()
        store.update { current ->
            current.copy(
                // One in-flight request per server; stale ones never linger.
                pending = current.pending.filterValues {
                    it.serverId != serverId && now - it.createdAtMs < McpOAuthDefaults.PENDING_TTL_MS
                } + (state to pending),
            )
        }
        return buildAuthorizationUrl(
            authorizationEndpoint = metadata.authorizationEndpoint,
            clientId = registration.clientId,
            redirectUri = McpOAuthDefaults.REDIRECT_URI,
            state = state,
            codeChallenge = codeChallengeS256(verifier),
            resource = resource,
            scope = requestedScope,
        )
    }

    /** Entry point for the redirect activity. Fire-and-forget; the outcome goes to [onResult]. */
    fun complete(redirect: String) {
        scope.launch(Dispatchers.IO) {
            try {
                onResult(exchangeRedirect(redirect), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e("McpOAuth", "MCP OAuth redirect failed", e)
                // Without a known server there is nobody to tell; the pending entry is gone.
                (e as? McpOAuthFlowException)?.let {
                    onResult(it.serverId, it.message ?: "Sign-in failed")
                }
            }
        }
    }

    /** Drops tokens but keeps the client registration, so signing in again needs no new DCR. */
    suspend fun signOut(serverId: String) {
        refreshLocks.computeIfAbsent(serverId) { Mutex() }.withLock {
            store.update { current ->
                current.copy(
                    records = current.records[serverId]
                        ?.let { current.records + (serverId to it.signedOut()) }
                        ?: current.records,
                )
            }
        }
        onResult(serverId, null)
    }

    /** The server was deleted: remove everything stored for it. */
    suspend fun forget(serverId: String) {
        challenges.remove(serverId)
        refreshLocks.remove(serverId)
        store.update { current ->
            current.copy(
                records = current.records - serverId,
                pending = current.pending.filterValues { it.serverId != serverId },
            )
        }
    }

    private suspend fun exchangeRedirect(redirect: String): String {
        val params = parseOAuthRedirect(redirect, McpOAuthDefaults.REDIRECT_URI)
            ?: throw IOException("Unexpected OAuth redirect")
        val state = params["state"] ?: throw IOException("OAuth redirect has no state")
        val pending = store.read().pending[state]
            ?: throw IOException("Unknown or expired sign-in request")
        // Single-use: consumed before anything else can fail, so a replay finds nothing.
        store.update { it.copy(pending = it.pending - state) }
        val serverId = pending.serverId
        try {
            if (clock() - pending.createdAtMs > McpOAuthDefaults.PENDING_TTL_MS) {
                throw IOException("The sign-in request expired. Try again.")
            }
            params["error"]?.let { error ->
                throw IOException(
                    "Authorization was not granted: $error" +
                        params["error_description"]?.let { " - $it" }.orEmpty(),
                )
            }
            val code = params["code"] ?: throw IOException("OAuth redirect has no code")
            val issuer = params["iss"]
            if (issuer != null && !sameIssuer(issuer, pending.issuer)) {
                throw IOException("Authorization response came from an unexpected issuer")
            }
            if (issuer == null && pending.issuerParameterRequired) {
                throw IOException("Authorization response is missing the issuer")
            }
            val config = serverConfig(serverId) ?: throw IOException("The MCP server was removed")
            if (canonicalMcpResource(config.url) != pending.resource) {
                throw IOException("The MCP server URL changed during sign-in")
            }
            val tokens = client.exchangeCode(pending, code)
            val record = McpOAuthRecord(
                resource = pending.resource,
                issuer = pending.issuer,
                tokenEndpoint = pending.tokenEndpoint,
                clientId = pending.clientId,
                clientSecret = pending.clientSecret,
                authMethod = pending.authMethod,
                registered = pending.registered,
                redirectUri = pending.redirectUri,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                expiresAtMs = tokens.expiresAtMs(clock()),
                scope = tokens.scope ?: pending.scope,
            )
            store.update { it.copy(records = it.records + (serverId to record)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Re-throw with the server id attached so the caller can report against it.
            throw McpOAuthFlowException(serverId, e)
        }
        return serverId
    }

    private class McpOAuthFlowException(val serverId: String, cause: Throwable) :
        IOException(cause.message, cause)

    private suspend fun resolveAuthorizationServer(
        protectedResource: McpProtectedResourceMetadata?,
        resource: String,
        challenge: McpAuthChallenge?,
    ): McpAuthServerMetadata {
        val origin = originOf(resource) ?: throw IOException("Invalid MCP server URL")
        val issuers = protectedResource?.authorizationServers.orEmpty().ifEmpty { listOf(origin) }
        var withoutPkce = false
        for (issuer in issuers) {
            val metadata = client.discoverAuthorizationServer(issuer) ?: continue
            if (metadata.supportsS256) return metadata
            withoutPkce = true
        }
        if (withoutPkce) {
            throw IOException("The authorization server does not support PKCE (S256)")
        }
        // MCP 2025-03-26 servers expose OAuth at fixed paths on their own origin.
        if (protectedResource == null && challenge != null) return legacyAuthServerDefaults(origin)
        throw IOException(
            "This server does not advertise OAuth. Check the URL or add an Authorization header.",
        )
    }

    private suspend fun resolveClient(
        config: McpServerConfig,
        metadata: McpAuthServerMetadata,
        existing: McpOAuthRecord?,
    ): ResolvedClient {
        val configured = config.oauthClientId.trim()
        if (configured.isNotEmpty()) {
            return ResolvedClient(configured, "", McpOAuthRecord.AUTH_METHOD_NONE, registered = false)
        }
        if (existing != null && existing.registered && existing.clientId.isNotBlank() &&
            existing.redirectUri == McpOAuthDefaults.REDIRECT_URI
        ) {
            return ResolvedClient(
                existing.clientId,
                existing.clientSecret,
                existing.authMethod,
                registered = true,
            )
        }
        if (metadata.registrationEndpoint == null) {
            throw IOException(
                "This server does not support automatic client registration. " +
                    "Enter an OAuth Client ID in the server settings.",
            )
        }
        val registration = client.registerClient(metadata, McpOAuthDefaults.REDIRECT_URI)
        return ResolvedClient(
            registration.clientId,
            registration.clientSecret,
            registration.authMethod,
            registered = true,
        )
    }

    private data class ResolvedClient(
        val clientId: String,
        val clientSecret: String,
        val authMethod: String,
        val registered: Boolean,
    )

    private suspend fun currentRecord(serverId: String, resource: String): McpOAuthRecord? =
        store.read().records[serverId]?.takeIf { it.resource == resource }

    /**
     * Returns a usable access token after at most one refresh, or null when signed out. A
     * transient network failure propagates (the connection loop backs off); a definitive
     * `invalid_grant` clears the tokens so the user is asked to sign in again.
     */
    private suspend fun refresh(serverId: String, resource: String, rejectedToken: String): String? =
        refreshLocks.computeIfAbsent(serverId) { Mutex() }.withLock {
            val record = currentRecord(serverId, resource) ?: return@withLock null
            // Another request refreshed while this one waited for the lock.
            if (record.accessToken.isNotBlank() && record.accessToken != rejectedToken &&
                !record.isExpired(clock())
            ) {
                return@withLock record.accessToken
            }
            if (record.refreshToken.isBlank()) return@withLock null
            try {
                val tokens = client.refresh(record)
                val updated = record.copy(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken.ifBlank { record.refreshToken },
                    expiresAtMs = tokens.expiresAtMs(clock()),
                    scope = tokens.scope ?: record.scope,
                )
                store.update { current ->
                    // Compare-and-set: a sign-out or newer sign-in that raced this refresh wins.
                    if (current.records[serverId]?.refreshToken == record.refreshToken) {
                        current.copy(records = current.records + (serverId to updated))
                    } else {
                        current
                    }
                }
                updated.accessToken
            } catch (e: McpOAuthException) {
                if (e.isInvalidGrant) {
                    store.update { current ->
                        current.records[serverId]
                            ?.takeIf { it.refreshToken == record.refreshToken }
                            ?.let { current.copy(records = current.records + (serverId to it.signedOut())) }
                            ?: current
                    }
                }
                null
            }
        }

    private inner class Session(
        private val serverId: String,
        private val resource: String,
    ) : McpAuthProvider {
        override suspend fun authorizationHeader(): String? {
            val record = currentRecord(serverId, resource) ?: return null
            if (record.accessToken.isNotBlank() && !record.isExpired(clock())) {
                return "Bearer ${record.accessToken}"
            }
            if (record.refreshToken.isBlank()) return null
            return refresh(serverId, resource, rejectedToken = record.accessToken)
                ?.let { "Bearer $it" }
        }

        override suspend fun onRejected(challenge: McpAuthChallenge, sentHeader: String?): Boolean {
            challenges[serverId] = challenge
            // A scope problem cannot be fixed by refreshing; it needs a new interactive grant.
            if (challenge.insufficientScope) return false
            val record = currentRecord(serverId, resource) ?: return false
            val sentToken = sentHeader?.removePrefix("Bearer ")
            // Someone else already replaced the rejected token: just retry with the new one.
            if (record.accessToken.isNotBlank() && record.accessToken != sentToken) return true
            if (record.refreshToken.isBlank()) return false
            return refresh(serverId, resource, rejectedToken = record.accessToken) != null
        }
    }
}
