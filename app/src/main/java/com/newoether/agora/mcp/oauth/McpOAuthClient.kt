package com.newoether.agora.mcp.oauth

import com.newoether.agora.api.HttpClient
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Credentials
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Network side of MCP OAuth: discovery, dynamic client registration, code exchange and refresh.
 * Stateless — persistence and flow sequencing belong to [McpOAuthManager]. Requests are blocking
 * OkHttp calls on [Dispatchers.IO] with bounded timeouts and bounded response bodies.
 */
internal class McpOAuthClient(
    private val http: OkHttpClient = HttpClient.client.newBuilder()
        .callTimeout(Constants.NETWORK_TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .connectTimeout(Constants.NETWORK_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns null when the server publishes no Protected Resource Metadata at any candidate. */
    suspend fun discoverProtectedResource(
        resource: String,
        challenge: McpAuthChallenge?,
    ): McpProtectedResourceMetadata? {
        for (candidate in protectedResourceMetadataCandidates(resource, challenge)) {
            if (!isAllowedOAuthUrl(candidate)) continue
            val body = getJson(candidate) ?: continue
            return parseProtectedResourceMetadata(body)
        }
        return null
    }

    /** Returns null when none of the RFC 8414 / OIDC candidates yields a valid document. */
    suspend fun discoverAuthorizationServer(issuer: String): McpAuthServerMetadata? {
        for (candidate in authorizationServerMetadataCandidates(issuer)) {
            if (!isAllowedOAuthUrl(candidate)) continue
            val body = getJson(candidate) ?: continue
            parseAuthServerMetadata(body, issuer)?.let { return it }
        }
        return null
    }

    /** RFC 7591 dynamic registration of a public native client with the app's redirect URI. */
    suspend fun registerClient(
        metadata: McpAuthServerMetadata,
        redirectUri: String,
    ): McpClientRegistration {
        val endpoint = metadata.registrationEndpoint
            ?: throw IOException("Authorization server does not support client registration")
        val method = chooseRegistrationAuthMethod(metadata.tokenEndpointAuthMethods)
        val payload = buildJsonObject {
            put("client_name", McpOAuthDefaults.CLIENT_NAME)
            put("redirect_uris", buildJsonArray { add(JsonPrimitive(redirectUri)) })
            put(
                "grant_types",
                buildJsonArray {
                    add(JsonPrimitive("authorization_code"))
                    add(JsonPrimitive("refresh_token"))
                },
            )
            put("response_types", buildJsonArray { add(JsonPrimitive("code")) })
            put("token_endpoint_auth_method", method)
        }
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val body = execute(request, "client registration")
        val clientId = body.str("client_id")
            ?: throw IOException("Client registration returned no client_id")
        val secret = body.str("client_secret").orEmpty()
        val granted = body.str("token_endpoint_auth_method")
            ?: if (secret.isBlank()) McpOAuthRecord.AUTH_METHOD_NONE else method
        return McpClientRegistration(
            clientId = clientId,
            clientSecret = secret,
            authMethod = if (secret.isBlank()) McpOAuthRecord.AUTH_METHOD_NONE else granted,
        )
    }

    suspend fun exchangeCode(pending: McpOAuthPending, code: String): McpTokenResponse =
        tokenRequest(
            tokenEndpoint = pending.tokenEndpoint,
            clientId = pending.clientId,
            clientSecret = pending.clientSecret,
            authMethod = pending.authMethod,
            fields = linkedMapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to pending.redirectUri,
                "code_verifier" to pending.codeVerifier,
                "resource" to pending.resource,
            ),
        )

    suspend fun refresh(record: McpOAuthRecord): McpTokenResponse =
        tokenRequest(
            tokenEndpoint = record.tokenEndpoint,
            clientId = record.clientId,
            clientSecret = record.clientSecret,
            authMethod = record.authMethod,
            fields = linkedMapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to record.refreshToken,
                "resource" to record.resource,
            ),
        )

    private suspend fun tokenRequest(
        tokenEndpoint: String,
        clientId: String,
        clientSecret: String,
        authMethod: String,
        fields: Map<String, String>,
    ): McpTokenResponse {
        if (!isAllowedOAuthUrl(tokenEndpoint)) {
            throw IOException("Refusing to use an insecure token endpoint")
        }
        val form = FormBody.Builder().apply {
            fields.forEach { (name, value) -> add(name, value) }
            when {
                authMethod == McpOAuthRecord.AUTH_METHOD_POST && clientSecret.isNotBlank() -> {
                    add("client_id", clientId)
                    add("client_secret", clientSecret)
                }
                authMethod == McpOAuthRecord.AUTH_METHOD_BASIC -> Unit
                else -> add("client_id", clientId)
            }
        }.build()
        val request = Request.Builder()
            .url(tokenEndpoint)
            .header("Accept", "application/json")
            .apply {
                if (authMethod == McpOAuthRecord.AUTH_METHOD_BASIC && clientSecret.isNotBlank()) {
                    header("Authorization", Credentials.basic(clientId, clientSecret))
                }
            }
            .post(form)
            .build()
        val body = execute(request, "token request")
        val accessToken = body.str("access_token")
            ?: throw IOException("Token response contained no access_token")
        val tokenType = body.str("token_type")
        if (tokenType != null && !tokenType.equals("bearer", ignoreCase = true)) {
            throw IOException("Unsupported token type: $tokenType")
        }
        return McpTokenResponse(
            accessToken = accessToken,
            refreshToken = body.str("refresh_token").orEmpty(),
            expiresInSeconds = (body["expires_in"] as? JsonPrimitive)?.let {
                it.longOrNull ?: it.contentOrNull?.toLongOrNull()
            }?.takeIf { it > 0 },
            scope = body.str("scope"),
        )
    }

    private suspend fun getJson(url: String): JsonObject? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parseObject(readBounded(response))
            }
        } catch (_: IOException) {
            null
        }
    }

    private suspend fun execute(request: Request, what: String): JsonObject =
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val text = readBounded(response)
                val body = parseObject(text)
                if (!response.isSuccessful) {
                    val code = body?.str("error")
                    val description = body?.str("error_description")
                    throw McpOAuthException(
                        error = code,
                        message = buildString {
                            append("OAuth ").append(what).append(" failed (HTTP ")
                            append(response.code).append(')')
                            if (code != null) append(": ").append(code)
                            if (description != null) append(" - ").append(description)
                        },
                        httpCode = response.code,
                    )
                }
                body ?: throw IOException("OAuth $what returned an invalid response")
            }
        }

    private fun readBounded(response: Response): String =
        response.peekBody(MAX_BODY_BYTES).string()

    private fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private companion object {
        const val MAX_BODY_BYTES = 256L * 1024L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
