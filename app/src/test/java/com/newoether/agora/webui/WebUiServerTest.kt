package com.newoether.agora.webui

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUiServerTest {
    private val hasher = WebUiPasswordHasher(iterations = 1_000)
    private val assets = mapOf(
        "index.html" to "<!doctype html><title>Agora</title>".toByteArray(),
        "app.js" to "export {}".toByteArray(),
    )

    @Test
    fun servesPackagedAssetsWithHardeningHeadersAndRejectsTraversal() = webUi { _ ->
        val index = client.get("/")
        assertEquals(HttpStatusCode.OK, index.status)
        assertTrue(index.headers[HttpHeaders.ContentType]!!.startsWith("text/html"))
        assertEquals("DENY", index.headers["X-Frame-Options"])
        assertTrue(index.headers["Content-Security-Policy"]!!.contains("frame-ancestors 'none'"))

        val script = client.get("/assets/app.js")
        assertTrue(script.headers[HttpHeaders.ContentType]!!.startsWith("text/javascript"))

        assertEquals(HttpStatusCode.NotFound, client.get("/assets/missing.js").status)
        assertFalse(WebUiServer.isSafeAssetPath("../secret"))
        assertFalse(WebUiServer.isSafeAssetPath("a/../b.js"))
        assertFalse(WebUiServer.isSafeAssetPath("/abs.js"))
        assertFalse(WebUiServer.isSafeAssetPath("a%2Fb.js"))
        assertTrue(WebUiServer.isSafeAssetPath("vendor/preact.mjs"))
    }

    @Test
    fun loginSetsAStrictHttpOnlySessionCookieAndLogoutClearsIt() = webUi { auth ->
        val login = login("pw")
        assertEquals(HttpStatusCode.OK, login.status)
        val cookie = login.headers.getAll(HttpHeaders.SetCookie)!!.single()
        assertTrue(cookie.startsWith("${WebUiServer.SESSION_COOKIE}="))
        assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("SameSite=Strict"))
        val token = cookie.substringAfter('=').substringBefore(';')
        assertTrue(auth.isValidSession(token))

        val session = client.get("/api/session") {
            header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
        }
        assertEquals("""{"signedIn":true}""", session.bodyAsText())

        val logout = client.post("/api/logout") {
            header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertFalse(auth.isValidSession(token))
        assertEquals("""{"signedIn":false}""", client.get("/api/session").bodyAsText())
    }

    @Test
    fun wrongPasswordsReportAttemptsLeftThenLockWithRetryAfter() = webUi { _ ->
        val first = login("bad")
        assertEquals(HttpStatusCode.Unauthorized, first.status)
        assertEquals("""{"error":"wrong_password","attemptsLeft":9}""", first.bodyAsText())
        repeat(8) { login("bad") }

        val locked = login("bad")
        assertEquals(HttpStatusCode.TooManyRequests, locked.status)
        assertEquals("300", locked.headers[HttpHeaders.RetryAfter])
        assertTrue(locked.headers.getAll(HttpHeaders.SetCookie).isNullOrEmpty())
        assertEquals(HttpStatusCode.TooManyRequests, login("pw").status)
    }

    @Test
    fun crossOriginAndNonJsonPostsAreRefusedBeforeAnyPasswordCheck() = webUi { auth ->
        val crossSite = client.post("/api/login") {
            header(HttpHeaders.Host, "192.168.1.5:8686")
            header(HttpHeaders.Origin, "http://evil.example")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"pw"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, crossSite.status)

        val form = client.post("/api/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("password=pw")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, form.status)

        val otherPort = client.post("/api/login") {
            header(HttpHeaders.Host, "192.168.1.5:8686")
            header(HttpHeaders.Origin, "http://192.168.1.5:9999")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"pw"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, otherPort.status)

        // Neither refused request counted as a failed password.
        assertEquals("""{"error":"wrong_password","attemptsLeft":9}""", login("bad").bodyAsText())
        // Browsers always send Host; the test engine does not, so set it like a browser would.
        val sameOrigin = client.post("/api/login") {
            header(HttpHeaders.Host, "192.168.1.5:8686")
            header(HttpHeaders.Origin, "http://192.168.1.5:8686")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"pw"}""")
        }
        assertEquals(HttpStatusCode.OK, sameOrigin.status)
        auth.revokeAllSessions()
    }

    @Test
    fun loginWithoutAPasswordConfiguredIsUnavailable() = webUi(hash = null) { _ ->
        val response = login("anything")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("""{"error":"not_configured"}""", response.bodyAsText())
    }

    @Test
    fun malformedOrOversizedLoginBodiesAreRejected() = webUi { _ ->
        val malformed = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("not json")
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)

        val oversized = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"${"x".repeat(5_000)}"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
    }

    private suspend fun ApplicationTestBuilder.login(password: String, origin: String? = null) =
        client.post("/api/login") {
            origin?.let { header(HttpHeaders.Origin, it) }
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$password"}""")
        }

    private fun webUi(
        hash: String? = hasher.hash("pw"),
        block: suspend ApplicationTestBuilder.(WebUiAuth) -> Unit,
    ) {
        val auth = WebUiAuth(passwordHash = { hash }, hasher = hasher, clock = { 0L })
        val server = WebUiServer(auth = auth, readAsset = assets::get, clock = { 0L })
        testApplication {
            application { server.install(this) }
            block(auth)
        }
    }
}
