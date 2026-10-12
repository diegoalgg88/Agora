package com.newoether.agora.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog contracts for [popularMcpServers]. Key invariant: a server intended for
 * MCP OAuth sign-in must NOT prefill an Authorization header — McpRegistry.authFor
 * disables OAuth whenever the saved config has any Authorization header, even empty.
 */
class PopularMcpServersTest {

    private fun entry(name: String): PopularMcpServer =
        popularMcpServers.first { it.name == name }

    @Test
    fun tavilyUsesOAuthWithoutAuthorizationPrefill() {
        val tavily = entry("Tavily")
        assertTrue("Tavily should opt into OAuth", tavily.oauth)
        assertTrue(tavily.requiresAuth)
        assertTrue(
            "OAuth servers must not prefill Authorization or the sign-in flow never engages",
            tavily.headers.keys.none { it.equals("authorization", ignoreCase = true) },
        )
    }

    @Test
    fun composioPointsAtConnectEndpointWithConsumerKeyHeader() {
        val composio = entry("Composio")
        assertEquals("https://connect.composio.dev/mcp", composio.url)
        assertTrue(composio.requiresAuth)
        assertFalse("Composio 401 carries no RFC 9728 challenge; OAuth cannot engage", composio.oauth)
        assertTrue(
            "Composio prefills the x-consumer-api-key header for the user's ck- key",
            composio.headers.containsKey("x-consumer-api-key"),
        )
        assertFalse(composio.headers.containsKey("x-api-key"))
    }

    @Test
    fun exaCarriesApiKeyInUrlQueryWithoutAuthorizationHeader() {
        val exa = entry("Exa")
        assertEquals("https://mcp.exa.ai/mcp?exaApiKey=", exa.url)
        assertTrue(exa.requiresAuth)
        assertFalse(exa.oauth)
        assertTrue("Exa authenticates via query param, not headers", exa.headers.isEmpty())
    }

    @Test
    fun jinaKeepsAuthorizationPrefillForApiKeyEntry() {
        val jina = entry("Jina AI")
        assertTrue(jina.requiresAuth)
        assertFalse(jina.oauth)
        assertTrue(jina.headers.containsKey("Authorization"))
    }

    @Test
    fun oauthServersStayKeyless() {
        val oauthEntries = popularMcpServers.filter { it.oauth }
        assertTrue("Expected Notion, Linear, Sentry and Tavily as OAuth entries", oauthEntries.size >= 4)
        oauthEntries.forEach { server ->
            assertTrue(
                "${server.name} declares OAuth but prefills Authorization, blocking sign-in",
                server.headers.keys.none { it.equals("authorization", ignoreCase = true) },
            )
        }
    }

    @Test
    fun noDuplicateUrlsInCatalog() {
        val urls = popularMcpServers.map { it.url.lowercase().trimEnd('/') }
        assertEquals(popularMcpServers.size, urls.toSet().size)
    }
}
