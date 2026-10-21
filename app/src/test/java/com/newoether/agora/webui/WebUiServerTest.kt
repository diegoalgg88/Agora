package com.newoether.agora.webui

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ReceiveChannel
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
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class WebUiServerTest {
    private val hasher = WebUiPasswordHasher(iterations = 1_000)
    private val assets = mapOf(
        "index.html" to "<!doctype html><title>Agora</title>".toByteArray(),
        "app.js" to "export {}".toByteArray(),
    )

    @get:Rule val temporary = TemporaryFolder()

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

    @Test
    fun syncRefusesTheUpgradeWithoutASessionOrFromAnotherOrigin() {
        var served = 0
        webUi(syncSession = { _, _, _ -> served++ }) { _ ->
            val sockets = createClient { install(ClientWebSockets) }
            val anonymous = runCatching { sockets.webSocket("/api/sync") {} }
            assertTrue(anonymous.isFailure)
            val token = sessionToken()
            val crossSite = runCatching {
                sockets.webSocket("/api/sync", request = {
                    header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                    header(HttpHeaders.Host, "192.168.1.5:8686")
                    header(HttpHeaders.Origin, "http://evil.example")
                }) {}
            }
            assertTrue(crossSite.isFailure)
            assertEquals(0, served)
            // The same browser from its own origin is let through.
            sockets.webSocket("/api/sync", request = {
                header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                header(HttpHeaders.Host, "192.168.1.5:8686")
                header(HttpHeaders.Origin, "http://192.168.1.5:8686")
            }) {}
        }
        assertEquals(1, served)
    }

    @Test
    fun syncServesTextFramesAndClosesWhenTheSessionEnds() = webUi(
        syncSession = { login, incoming, send -> for (text in incoming) send("echo:$text") },
    ) { auth ->
        val token = sessionToken()
        val sockets = createClient { install(ClientWebSockets) }
        sockets.webSocket("/api/sync", request = {
            header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
        }) {
            send(Frame.Text("hello"))
            assertEquals("echo:hello", (incoming.receive() as Frame.Text).readText())
            auth.logout(token)
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
    }

    @Test
    fun attachmentPreviewStreamsBoundedOpenAndSuffixRangesThroughTheSameOwner() {
        val file = temporary.newFile("range.mp4").apply { writeText("0123456789") }
        var settled = 0
        webUi(previewAttachment = { _, _, _, _, _, _, consume ->
            try { consume(file, "video/mp4"); true } finally { settled++ }
        }) { auth ->
            val token = sessionToken()
            suspend fun request(range: String, signedIn: Boolean = true, ifRange: String? = null) = client.get("/api/attachments/tab/pick/source/0?seq=7") {
                if (signedIn) header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                header(HttpHeaders.Range, range)
                ifRange?.let { header(HttpHeaders.IfRange, it) }
            }
            assertEquals(HttpStatusCode.Forbidden, request("bytes=2-4", false).status)
            assertEquals(0, settled)
            for ((range, expected, header) in listOf(
                Triple("bytes=2-4", "234", "bytes 2-4/10"),
                Triple("bytes=7-", "789", "bytes 7-9/10"),
                Triple("bytes=-2", "89", "bytes 8-9/10"),
                Triple("bytes=8-99", "89", "bytes 8-9/10"),
            )) {
                val response = request(range)
                assertEquals(HttpStatusCode.PartialContent, response.status)
                assertEquals(expected, response.bodyAsText())
                assertEquals(header, response.headers[HttpHeaders.ContentRange])
                assertEquals(expected.length.toString(), response.headers[HttpHeaders.ContentLength])
                assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            }
            val unsatisfiable = request("bytes=10-")
            assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, unsatisfiable.status)
            assertEquals("bytes */10", unsatisfiable.headers[HttpHeaders.ContentRange])
            for (ignored in listOf("bytes=bad", "bytes=0-1,8-9", "items=0-1")) {
                val response = request(ignored)
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("0123456789", response.bodyAsText())
            }
            assertEquals("0123456789", request("bytes=2-4", ifRange = "unknown").bodyAsText())
            assertEquals(9, settled)
            auth.logout(token)
            assertEquals(HttpStatusCode.Forbidden, request("bytes=2-4").status)
            assertEquals(9, settled)
        }
    }

    private suspend fun ApplicationTestBuilder.sessionToken(): String =
        login("pw").headers.getAll(HttpHeaders.SetCookie)!!.single()
            .substringAfter('=').substringBefore(';')

    private fun webUi(
        hash: String? = hasher.hash("pw"),
        syncSession: suspend (String, ReceiveChannel<String>, suspend (String) -> Unit) -> Unit = { _, _, _ -> },
        previewAttachment: suspend (String, String, Long, String, String, Int, suspend (java.io.File, String) -> Unit) -> Boolean =
            { _, _, _, _, _, _, _ -> false },
        block: suspend ApplicationTestBuilder.(WebUiAuth) -> Unit,
    ) {
        val auth = WebUiAuth(passwordHash = { hash }, hasher = hasher, clock = { 0L })
        val server = WebUiServer(
            auth = auth,
            readAsset = assets::get,
            syncSession = syncSession,
            previewAttachment = previewAttachment,
            clock = { 0L },
        )
        testApplication {
            application { server.install(this) }
            block(auth)
        }
    }
}
