package com.newoether.agora.data

import com.newoether.agora.data.local.ChatEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBackupFormatTest {
    @Test
    fun versionPolicy_acceptsOnlyKnownNativeFormats() {
        assertFalse(NativeBackupFormat.isSupported(0))
        assertTrue(NativeBackupFormat.isSupported(1))
        assertTrue(NativeBackupFormat.isSupported(NativeBackupFormat.CURRENT_VERSION))
        assertFalse(NativeBackupFormat.isSupported(NativeBackupFormat.CURRENT_VERSION + 1))
    }

    @Test
    fun portableCompositeSettings_removeEveryCredential() {
        val shell = ShellDeviceConfig(
            name = "server",
            type = "ssh",
            apiKey = "conch-secret",
            sshPassword = "ssh-secret",
            sshHostKey = "public-host-pin",
        ).withoutSecrets()
        assertEquals("", shell.apiKey)
        assertEquals("", shell.sshPassword)
        assertEquals("public-host-pin", shell.sshHostKey)

        val mcp = McpServerConfig(
            name = "mcp",
            url = "https://mcp.tavily.com/mcp/?tavilyApiKey=tvly-secret&humanId=h1",
            headers = mapOf("Authorization" to "Bearer mcp-secret"),
            disabledTools = setOf("dangerous"),
        ).withoutSecrets()
        assertTrue(mcp.headers.isEmpty())
        assertEquals(setOf("dangerous"), mcp.disabledTools)
        assertEquals("https://mcp.tavily.com/mcp/?humanId=h1", mcp.url)

        val remote = EmbeddingModelConfig(
            name = "remote",
            type = EmbeddingModelType.REMOTE,
            remoteModelName = "embed",
            remoteBaseUrl = "https://example.test",
            remoteApiKey = "embedding-secret",
            localFilePath = "/data/user/0/private.gguf",
        ).asPortableRemoteConfig()
        requireNotNull(remote)
        assertEquals("", remote.remoteApiKey)
        assertEquals("", remote.localFilePath)

        val local = EmbeddingModelConfig(
            name = "local",
            type = EmbeddingModelType.LOCAL,
            localFilePath = "/data/user/0/private.gguf",
        ).asPortableRemoteConfig()
        assertNull(local)

        val portableJson = Json.encodeToString(listOf(shell)) +
            Json.encodeToString(listOf(mcp)) +
            Json.encodeToString(listOf(remote))
        assertFalse(portableJson.contains("conch-secret"))
        assertFalse(portableJson.contains("ssh-secret"))
        assertFalse(portableJson.contains("mcp-secret"))
        assertFalse(portableJson.contains("tvly-secret"))
        assertFalse(portableJson.contains("embedding-secret"))
        assertFalse(portableJson.contains("/data/user/0"))
    }

    @Test
    fun mcpUrlCredentialParameters_areDetectedAndSanitized() {
        assertTrue(isMcpUrlCredentialParameter("tavilyApiKey"))
        assertTrue(isMcpUrlCredentialParameter("api_key"))
        assertTrue(isMcpUrlCredentialParameter("access-token"))
        assertTrue(isMcpUrlCredentialParameter("clientSecret"))
        assertTrue(isMcpUrlCredentialParameter("key"))
        assertTrue(isMcpUrlCredentialParameter("token"))
        assertFalse(isMcpUrlCredentialParameter("keyword"))
        assertFalse(isMcpUrlCredentialParameter("humanId"))
        assertFalse(isMcpUrlCredentialParameter("session"))
        assertFalse(isMcpUrlCredentialParameter("routing"))

        assertTrue(
            hasMcpUrlCredentialParameters("https://mcp.tavily.com/mcp/?tavilyApiKey=tvly-x"),
        )
        assertFalse(hasMcpUrlCredentialParameters("https://mcp.tavily.com/mcp/?humanId=h1"))
        assertFalse(hasMcpUrlCredentialParameters("not a url"))

        assertEquals(
            "https://mcp.tavily.com/mcp/?humanId=h1",
            sanitizeMcpUrlCredentials("https://mcp.tavily.com/mcp/?tavilyApiKey=tvly-x&humanId=h1"),
        )
        assertEquals(
            "https://api.example.test/v1?client_id=abc",
            sanitizeMcpUrlCredentials("https://api.example.test/v1?client_id=abc&client_secret=cs-x"),
        )
        val clean = "https://mcp.example.test/mcp"
        assertEquals(clean, sanitizeMcpUrlCredentials(clean))
        val malformed = "::::not-parseable::::"
        assertEquals(malformed, sanitizeMcpUrlCredentials(malformed))
    }

    @Test
    fun secretsPayload_usesStableIdsAndContainsAllCredentialKinds() {
        val data = NativeBackupSecrets(
            proxyPassword = "proxy-secret",
            shellDevices = mapOf(
                "device-id" to ShellDeviceSecrets(
                    apiKey = "shell-key",
                    sshPassword = "shell-password",
                ),
            ),
            embeddingApiKeys = mapOf("embedding-id" to "embedding-key"),
            mcpHeaders = mapOf(
                "mcp-id" to mapOf("Authorization" to "Bearer mcp-key"),
            ),
            mcpUrls = mapOf(
                "mcp-id" to "https://mcp.tavily.com/mcp/?tavilyApiKey=tvly-url-key",
            ),
        )
        val encoded = Json.encodeToString(data)
        assertTrue(encoded.contains("\"device-id\""))
        assertTrue(encoded.contains("proxy-secret"))
        assertTrue(encoded.contains("shell-password"))
        assertTrue(encoded.contains("embedding-key"))
        assertTrue(encoded.contains("mcp-key"))
        assertTrue(encoded.contains("tvly-url-key"))
    }

    @Test
    fun importedConversation_neverRestoresUnreadDeviceState() {
        val restored = sanitizeImportedConversation(
            conversation = ChatEntity(
                id = "conversation",
                title = "Title",
                hasUnreadGeneration = true,
            ),
            availableTaskIds = emptySet(),
        )
        assertFalse(restored.hasUnreadGeneration)
    }
}

