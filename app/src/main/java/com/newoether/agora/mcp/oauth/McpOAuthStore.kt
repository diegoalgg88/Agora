package com.newoether.agora.mcp.oauth

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.newoether.agora.data.MCP_OAUTH_JSON
import com.newoether.agora.data.dataStore
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.SecretCrypto
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable OAuth state: tokens, client registrations and in-flight authorization requests.
 *
 * Everything lives in one DataStore value encrypted with [SecretCrypto]. [update] is the only
 * writer and runs its transform inside `DataStore.edit`, so a read-modify-write is atomic across
 * concurrent refreshes, sign-outs and redirects. The state is device-local: it is not part of
 * the portable settings allowlist and never enters a `.agora` archive.
 */
internal class McpOAuthStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun read(): McpOAuthState = decode(context.dataStore.data.first()[MCP_OAUTH_JSON])

    suspend fun update(transform: (McpOAuthState) -> McpOAuthState) {
        context.dataStore.edit { preferences ->
            val next = transform(decode(preferences[MCP_OAUTH_JSON]))
            preferences[MCP_OAUTH_JSON] = SecretCrypto.encrypt(json.encodeToString(next))
        }
    }

    // A value that cannot be decrypted or parsed fails closed to "signed out": the user signs in
    // again, nothing is guessed.
    private fun decode(stored: String?): McpOAuthState {
        if (stored.isNullOrBlank()) return McpOAuthState()
        return try {
            json.decodeFromString<McpOAuthState>(SecretCrypto.decrypt(stored))
        } catch (error: Exception) {
            DebugLog.e("McpOAuthStore", "Discarding unreadable MCP OAuth state", error)
            McpOAuthState()
        }
    }
}
