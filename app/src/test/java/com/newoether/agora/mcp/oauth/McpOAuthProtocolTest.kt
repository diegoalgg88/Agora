package com.newoether.agora.mcp.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthProtocolTest {
    @Test
    fun bearerChallengeCarriesMetadataUrlScopeAndError() {
        val challenge = parseBearerChallenge(
            listOf(
                "Bearer realm=\"mcp\", resource_metadata=\"https://mcp.example.com/.well-known/oauth-protected-resource\", scope=\"read write\", error=\"insufficient_scope\"",
            ),
        )
        assertNotNull(challenge)
        assertEquals(
            "https://mcp.example.com/.well-known/oauth-protected-resource",
            challenge!!.resourceMetadataUrl,
        )
        assertEquals("read write", challenge.scope)
        assertTrue(challenge.insufficientScope)
    }

    @Test
    fun bearerChallengeIsFoundAfterOtherSchemesAndIgnoresNonBearer() {
        assertNull(parseBearerChallenge(listOf("Basic realm=\"x\"")))
        val challenge = parseBearerChallenge(
            listOf("Basic realm=\"x\", Bearer resource_metadata=\"https://a.example/m\""),
        )
        assertEquals("https://a.example/m", challenge?.resourceMetadataUrl)
    }

    @Test
    fun bareBearerChallengeHasNoParameters() {
        val challenge = parseBearerChallenge(listOf("Bearer"))
        assertNotNull(challenge)
        assertNull(challenge!!.resourceMetadataUrl)
        assertFalse(challenge.insufficientScope)
    }

    @Test
    fun quotedValuesMayContainCommasAndEscapes() {
        val challenge = parseBearerChallenge(
            listOf("Bearer scope=\"a,b \\\"c\\\"\", error=invalid_token"),
        )
        assertEquals("a,b \"c\"", challenge?.scope)
        assertEquals("invalid_token", challenge?.error)
    }

    @Test
    fun canonicalResourceDropsQueryFragmentPortAndTrailingSlash() {
        assertEquals(
            "https://mcp.example.com/mcp",
            canonicalMcpResource("HTTPS://MCP.Example.com:443/mcp/?tavilyApiKey=secret#x"),
        )
        assertEquals("https://mcp.example.com", canonicalMcpResource("https://mcp.example.com/"))
        assertEquals("http://localhost:8080/mcp", canonicalMcpResource("http://localhost:8080/mcp"))
        assertNull(canonicalMcpResource("not a url"))
    }

    @Test
    fun protectedResourceMustCoverTheServerUrl() {
        assertTrue(protectedResourceMatches("https://a.example/mcp", "https://a.example/mcp"))
        assertTrue(protectedResourceMatches("https://a.example", "https://a.example/mcp"))
        assertFalse(protectedResourceMatches("https://evil.example", "https://a.example/mcp"))
        assertFalse(protectedResourceMatches("https://a.example/mc", "https://a.example/mcp"))
    }

    @Test
    fun metadataCandidatesFollowTheSpecOrder() {
        assertEquals(
            listOf(
                "https://x.example/meta",
                "https://a.example/.well-known/oauth-protected-resource/mcp",
                "https://a.example/.well-known/oauth-protected-resource",
            ),
            protectedResourceMetadataCandidates(
                "https://a.example/mcp",
                McpAuthChallenge(resourceMetadataUrl = "https://x.example/meta"),
            ),
        )
        assertEquals(
            listOf(
                "https://as.example/.well-known/oauth-authorization-server/tenant",
                "https://as.example/.well-known/openid-configuration/tenant",
                "https://as.example/tenant/.well-known/openid-configuration",
            ),
            authorizationServerMetadataCandidates("https://as.example/tenant"),
        )
        assertEquals(
            listOf(
                "https://as.example/.well-known/oauth-authorization-server",
                "https://as.example/.well-known/openid-configuration",
            ),
            authorizationServerMetadataCandidates("https://as.example"),
        )
    }

    @Test
    fun pkceChallengeMatchesRfc7636Vector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            codeChallengeS256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
        val verifier = generateCodeVerifier()
        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun authorizationUrlCarriesPkceStateAndResource() {
        val url = buildAuthorizationUrl(
            authorizationEndpoint = "https://as.example/authorize",
            clientId = "client-1",
            redirectUri = McpOAuthDefaults.REDIRECT_URI,
            state = "st",
            codeChallenge = "ch",
            resource = "https://a.example/mcp",
            scope = "read",
        ).toHttpUrl()
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("client-1", url.queryParameter("client_id"))
        assertEquals(McpOAuthDefaults.REDIRECT_URI, url.queryParameter("redirect_uri"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("ch", url.queryParameter("code_challenge"))
        assertEquals("st", url.queryParameter("state"))
        assertEquals("https://a.example/mcp", url.queryParameter("resource"))
        assertEquals("read", url.queryParameter("scope"))
    }

    @Test
    fun redirectParsingAcceptsOnlyTheAppRedirect() {
        val params = parseOAuthRedirect(
            "com.newoether.agora:/oauth/mcp?code=a%20b&state=xyz",
            McpOAuthDefaults.REDIRECT_URI,
        )
        assertEquals(mapOf("code" to "a b", "state" to "xyz"), params)
        assertNull(parseOAuthRedirect("com.newoether.agora:/other?code=1", McpOAuthDefaults.REDIRECT_URI))
        assertNull(
            parseOAuthRedirect("https://evil.example/oauth/mcp?code=1", McpOAuthDefaults.REDIRECT_URI),
        )
    }

    @Test
    fun authServerMetadataRejectsIssuerMismatchAndInsecureEndpoints() {
        fun doc(issuer: String, token: String = "https://as.example/token") =
            Json.parseToJsonElement(
                "{\"issuer\":\"$issuer\",\"authorization_endpoint\":\"https://as.example/authorize\"," +
                    "\"token_endpoint\":\"$token\",\"registration_endpoint\":\"https://as.example/register\"," +
                    "\"code_challenge_methods_supported\":[\"S256\"]}",
            ).jsonObject
        val ok = parseAuthServerMetadata(doc("https://as.example"), "https://as.example/")
        assertNotNull(ok)
        assertTrue(ok!!.supportsS256)
        assertNull(parseAuthServerMetadata(doc("https://other.example"), "https://as.example"))
        assertNull(
            parseAuthServerMetadata(doc("https://as.example", "http://as.example/token"), "https://as.example"),
        )
    }

    @Test
    fun missingPkceAdvertisementIsNotS256ExceptForLegacyDefaults() {
        val md = McpAuthServerMetadata("https://as.example", "https://as.example/a", "https://as.example/t")
        assertFalse(md.supportsS256)
        assertTrue(legacyAuthServerDefaults("https://as.example").supportsS256)
    }

    @Test
    fun oauthUrlsMustBeHttpsOrLocal() {
        assertTrue(isAllowedOAuthUrl("https://as.example/token"))
        assertTrue(isAllowedOAuthUrl("http://localhost:3000/token"))
        assertFalse(isAllowedOAuthUrl("http://as.example/token"))
        assertFalse(isAllowedOAuthUrl("https://user:pw@as.example/token"))
    }

    @Test
    fun scopesMergeWithoutDuplicates() {
        assertEquals("a b c", mergeScopes("a b", "b c", null))
        assertNull(mergeScopes(null, " "))
    }

    @Test
    fun registrationPrefersPublicClients() {
        assertEquals("none", chooseRegistrationAuthMethod(null))
        assertEquals("none", chooseRegistrationAuthMethod(listOf("client_secret_basic", "none")))
        assertEquals(
            "client_secret_post",
            chooseRegistrationAuthMethod(listOf("client_secret_basic", "client_secret_post")),
        )
    }

    @Test
    fun tokenExpiryHonoursSkewAndUnknownLifetime() {
        val record = McpOAuthRecord(
            resource = "https://a.example/mcp",
            issuer = "https://as.example",
            tokenEndpoint = "https://as.example/token",
            clientId = "c",
            redirectUri = McpOAuthDefaults.REDIRECT_URI,
            accessToken = "t",
            expiresAtMs = 100_000L,
        )
        assertFalse(record.isExpired(nowMs = 30_000L))
        assertTrue(record.isExpired(nowMs = 45_000L))
        assertFalse(record.copy(expiresAtMs = 0L).isExpired(nowMs = Long.MAX_VALUE / 2))
        assertFalse(record.signedOut().hasCredentials)
    }
}
