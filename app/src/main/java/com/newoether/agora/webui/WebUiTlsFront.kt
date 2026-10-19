package com.newoether.agora.webui

import com.newoether.agora.util.DebugLog
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

/**
 * HTTPS para el WebUI. El engine CIO de Ktor no tiene TLS, así que este escucha en el puerto
 * público, termina TLS con el stack de la plataforma y relee los bytes de cada conexión hacia
 * CIO, que escucha solo en loopback. El relay es byte a byte, así keep-alive de HTTP y los
 * upgrades de WebSocket pasan sin cambios.
 *
 * El primer byte de cada conexión distingue TLS (un record de handshake, `0x16`) de HTTP plano.
 * HTTP plano solo se contesta desde la propia dirección loopback del device, releyéndolo a
 * [plainBackendPort]; desde cualquier otra dirección se descarta, así una contraseña o cookie
 * jamás cruza la red sin cifrar.
 *
 * Lanza desde el constructor cuando el puerto no se puede bindear.
 */
internal class WebUiTlsFront(
    identity: WebUiTlsIdentity,
    publicPort: Int,
    private val tlsBackendPort: Int,
    private val plainBackendPort: Int,
    bindHost: String = WebUiController.ANY_HOST,
    /** Qué direcciones cliente pueden usar HTTP plano; los tests lo estrechan para ejercitar el drop. */
    private val allowsPlainFrom: (InetAddress) -> Boolean = InetAddress::isLoopbackAddress,
) : Closeable {
    private val serverSocket = ServerSocket()
    private val tlsContext: SSLContext
    private val openSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    @Volatile private var closed = false

    /** El puerto bindeado; igual al pedido salvo que haya sido 0. */
    val localPort: Int get() = serverSocket.localPort

    init {
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(identity.keyStore, identity.password) }
            .keyManagers
        tlsContext = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(bindHost, publicPort), BACKLOG)
        thread(name = "WebUiTlsAccept", isDaemon = true) { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!closed) {
            val client = try {
                serverSocket.accept()
            } catch (error: IOException) {
                if (closed || serverSocket.isClosed) return
                DebugLog.w(TAG, "WebUI accept failed", error)
                continue
            }
            thread(name = "WebUiTlsConnection", isDaemon = true) { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        val backend = Socket()
        openSockets += client
        openSockets += backend
        try {
            // Un cliente que no envía nada, o que nunca termina el handshake, se descarta.
            client.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
            val first = client.getInputStream().read()
            if (first < 0) return
            if (first == TLS_HANDSHAKE_RECORD) {
                val tls = WebUiTlsConnection(client, tlsContext, ALLOWED_PROTOCOLS, byteArrayOf(first.toByte()))
                tls.handshake()
                client.soTimeout = 0
                backend.connect(InetSocketAddress(LOOPBACK, tlsBackendPort), CONNECT_TIMEOUT_MILLIS)
                relay(
                    upstream = { ignoringClose { tls.readInto(backend.getOutputStream()) } },
                    downstream = { pump(backend.getInputStream()) { data, length -> tls.write(data, length) } },
                    client = client,
                    backend = backend,
                )
            } else {
                if (!allowsPlainFrom(client.inetAddress)) return
                client.soTimeout = 0
                backend.connect(InetSocketAddress(LOOPBACK, plainBackendPort), CONNECT_TIMEOUT_MILLIS)
                backend.getOutputStream().write(first)
                relay(
                    upstream = { pump(client.getInputStream()) { data, length -> backend.getOutputStream().write(data, 0, length) } },
                    downstream = { pump(backend.getInputStream()) { data, length -> client.getOutputStream().write(data, 0, length) } },
                    client = client,
                    backend = backend,
                )
            }
        } catch (_: IOException) {
            // Fallos de handshake y resets son rutina para un server en una red abierta.
        } finally {
            closeQuietly(client, backend)
            openSockets -= client
            openSockets -= backend
        }
    }

    /**
     * Corre ambas direcciones hasta que cualquiera termine: CIO cierra solo las conexiones
     * keep-alive idle, y una pestaña cerrada del navegador termina el lado upstream.
     */
    private fun relay(upstream: () -> Unit, downstream: () -> Unit, client: Socket, backend: Socket) {
        val up = thread(name = "WebUiTlsUpstream", isDaemon = true) {
            upstream()
            closeQuietly(client, backend)
        }
        downstream()
        closeQuietly(client, backend)
        up.join()
    }

    /** Copia [input] a [sink] hasta que termina o cualquiera de los lados cierra. */
    private fun pump(input: InputStream, sink: (ByteArray, Int) -> Unit) = ignoringClose {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return@ignoringClose
            sink(buffer, read)
        }
    }

    private inline fun ignoringClose(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            // El otro lado cerró.
        }
    }

    private fun closeQuietly(vararg sockets: Socket) {
        sockets.forEach { runCatching { it.close() } }
    }

    override fun close() {
        closed = true
        runCatching { serverSocket.close() }
        openSockets.toList().forEach { runCatching { it.close() } }
        openSockets.clear()
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        private const val TAG = "WebUiTls"
        private const val TLS_HANDSHAKE_RECORD = 0x16
        private val ALLOWED_PROTOCOLS = setOf("TLSv1.2", "TLSv1.3")
        private const val BACKLOG = 50
        private const val HANDSHAKE_TIMEOUT_MILLIS = 10_000
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val BUFFER_BYTES = 16 * 1024
    }
}
