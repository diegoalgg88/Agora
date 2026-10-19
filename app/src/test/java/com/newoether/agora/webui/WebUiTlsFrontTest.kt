package com.newoether.agora.webui

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebUiTlsFrontTest {
    private val dir: File = Files.createTempDirectory("webui-tls").toFile()
    private val identity = WebUiCertificateStore(
        directory = dir,
        seal = { it },
        unseal = { it },
        addresses = { listOf(InetAddress.getByName("127.0.0.1")) },
    ).loadOrCreate()
    @Volatile private var tlsPort = -1
    private val routes = WebUiServer(
        auth = WebUiAuth(
            passwordHash = { WebUiPasswordHasher(iterations = 1_000).hash("correct horse") },
            hasher = WebUiPasswordHasher(iterations = 1_000),
        ),
        readAsset = { path -> if (path == WebUiServer.INDEX) "<title>Agora</title>".toByteArray() else null },
        // As in WebUiController: only requests on the TLS backend connector get a Secure cookie.
        secureCookies = { call -> call.request.local.localPort == tlsPort },
    )
    private val backend = startWebUiEngine(port = 0, routes = routes, host = WebUiTlsFront.LOOPBACK, extraConnectors = 1)
    private val backendPorts = runBlocking { backend.engine.resolvedConnectors().map { it.port } }
    private val fronts = mutableListOf<WebUiTlsFront>()

    init {
        tlsPort = backendPorts[0]
    }

    private fun front(allowsPlainFrom: (InetAddress) -> Boolean = InetAddress::isLoopbackAddress) =
        WebUiTlsFront(
            identity,
            publicPort = 0,
            tlsBackendPort = backendPorts[0],
            plainBackendPort = backendPorts[1],
            bindHost = WebUiTlsFront.LOOPBACK,
            allowsPlainFrom = allowsPlainFrom,
        ).also(fronts::add)

    @After
    fun tearDown() {
        fronts.forEach(WebUiTlsFront::close)
        backend.stop(100, 500)
        dir.deleteRecursively()
    }

    /** A client that trusts exactly this self-signed certificate, as a browser does after accepting it. */
    private fun openTls(front: WebUiTlsFront, path: String): HttpsURLConnection {
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("webui", identity.certificate)
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(trust) }.trustManagers, null)
        }
        return (URL("https://127.0.0.1:${front.localPort}$path").openConnection() as HttpsURLConnection)
            .apply { sslSocketFactory = context.socketFactory }
    }

    private fun openPlain(front: WebUiTlsFront, path: String) =
        URL("http://127.0.0.1:${front.localPort}$path").openConnection() as HttpURLConnection

    private fun HttpURLConnection.login(): HttpURLConnection = apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        outputStream.use { it.write("""{"password":"correct horse"}""".toByteArray()) }
    }

    @Test
    fun servesPagesOverTlsWithTheStoredCertificate() {
        val connection = openTls(front(), "/")
        assertEquals(200, connection.responseCode)
        assertTrue(connection.inputStream.bufferedReader().readText().contains("Agora"))
        assertEquals(identity.certificate, connection.serverCertificates.first())
    }

    @Test
    fun loginOverTlsSetsASecureCookie() {
        val connection = openTls(front(), "/api/login").login()
        assertEquals(200, connection.responseCode)
        val cookie = connection.getHeaderField("Set-Cookie")
        assertTrue(cookie, cookie.contains("Secure"))
        assertTrue(cookie, cookie.contains("HttpOnly"))
    }

    @Test
    fun plainHttpFromLoopbackIsServedOnTheSamePortWithoutSecure() {
        val front = front()
        val page = openPlain(front, "/")
        assertEquals(200, page.responseCode)
        assertTrue(page.inputStream.bufferedReader().readText().contains("Agora"))
        // A Secure cookie would be dropped by the browser on plain HTTP and sign-in would fail.
        val login = openPlain(front, "/api/login").login()
        assertEquals(200, login.responseCode)
        val cookie = login.getHeaderField("Set-Cookie")
        assertTrue(cookie, cookie.contains("HttpOnly"))
        assertFalse(cookie, cookie.contains("Secure"))
    }

    @Test
    fun plainHttpFromAnotherAddressGetsNoResponse() {
        val connection = openPlain(front(allowsPlainFrom = { false }), "/")
        connection.readTimeout = 15_000
        try {
            connection.responseCode
            fail("plain HTTP must not be answered")
        } catch (_: IOException) {
            // The front drops a plain connection that is not from this device.
        }
    }
}
