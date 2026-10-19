package com.newoether.agora.webui

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

/**
 * Lado servidor de una conexión TLS sobre un [socket] ya aceptado, impulsado por un [SSLEngine].
 *
 * Un engine y no un `SSLSocket` porque [WebUiTlsFront] ya leyó el primer byte para distinguir
 * TLS de HTTP plano, y el `SSLSocketFactory` de Android no puede recibir bytes que ya se
 * consumieron del socket. [consumed] se alimenta al engine antes de leer cualquier otra cosa.
 *
 * Un thread puede llamar [readInto] mientras otro llama [write]; el engine permite que
 * `unwrap` y `wrap` corran concurrentes, y cada `wrap` pasa por [wrapLock].
 */
internal class WebUiTlsConnection(
    private val socket: Socket,
    context: SSLContext,
    protocols: Set<String>,
    consumed: ByteArray,
) {
    private val engine: SSLEngine = context.createSSLEngine().apply {
        useClientMode = false
        enabledProtocols = supportedProtocols.filter { it in protocols }.toTypedArray()
    }
    private val rawIn: InputStream = socket.getInputStream()
    private val rawOut: OutputStream = socket.getOutputStream()
    private val wrapLock = Any()

    // Ambos buffers permanecen en modo escritura entre llamadas.
    private var netIn: ByteBuffer = ByteBuffer.allocate(maxOf(engine.session.packetBufferSize, consumed.size))
        .put(consumed)
    private var appIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize)
    private var netOut: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)

    /** Completa el handshake; lanza cuando el peer no habla un TLS aceptable. */
    fun handshake() {
        engine.beginHandshake()
        while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP ->
                    if (!unwrap()) throw IOException("TLS peer closed during the handshake")
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrap(EMPTY)
                SSLEngineResult.HandshakeStatus.NEED_TASK -> runTasks()
                else -> return
            }
        }
    }

    /** Copia datos de aplicación descifrados a [output] hasta que el peer cierra. */
    fun readInto(output: OutputStream) {
        while (unwrap()) {
            if (appIn.position() == 0) continue
            appIn.flip()
            output.write(appIn.array(), appIn.arrayOffset(), appIn.limit())
            output.flush()
            appIn.clear()
        }
    }

    /** Cifra y envía [length] bytes de [data]. */
    fun write(data: ByteArray, length: Int) {
        val source = ByteBuffer.wrap(data, 0, length)
        synchronized(wrapLock) {
            while (source.hasRemaining()) wrap(source)
        }
    }

    /**
     * Un paso de `unwrap`, leyendo del socket cuando el engine necesita más bytes.
     * Retorna false cuando el peer o el engine cerraron.
     */
    private fun unwrap(): Boolean {
        netIn.flip()
        val result = try {
            engine.unwrap(netIn, appIn)
        } finally {
            netIn.compact()
        }
        when (result.status) {
            SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                if (netIn.remaining() < engine.session.packetBufferSize) {
                    netIn = grow(netIn, engine.session.packetBufferSize)
                }
                val read = rawIn.read(netIn.array(), netIn.arrayOffset() + netIn.position(), netIn.remaining())
                if (read < 0) return false
                netIn.position(netIn.position() + read)
            }
            SSLEngineResult.Status.BUFFER_OVERFLOW -> appIn = grow(appIn, engine.session.applicationBufferSize)
            SSLEngineResult.Status.CLOSED -> return false
            SSLEngineResult.Status.OK -> Unit
        }
        runTasks()
        // Mensajes post-handshake de TLS 1.3 (key updates) pueden pedir respuesta fuera del handshake.
        if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
            synchronized(wrapLock) { wrap(EMPTY) }
        }
        return true
    }

    /** Un paso de `wrap` y los bytes resultantes al wire. Llamar bajo [wrapLock] fuera del handshake. */
    private fun wrap(source: ByteBuffer) {
        while (true) {
            netOut.clear()
            val result = engine.wrap(source, netOut)
            when (result.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> netOut = ByteBuffer.allocate(netOut.capacity() * 2)
                SSLEngineResult.Status.CLOSED -> throw IOException("TLS connection closed")
                else -> {
                    netOut.flip()
                    rawOut.write(netOut.array(), netOut.arrayOffset(), netOut.limit())
                    rawOut.flush()
                    runTasks()
                    return
                }
            }
        }
    }

    private fun runTasks() {
        while (true) {
            val task = engine.delegatedTask ?: return
            task.run()
        }
    }

    /** Una copia más grande de un [buffer] en modo escritura con al menos [minimumFree] bytes libres. */
    private fun grow(buffer: ByteBuffer, minimumFree: Int): ByteBuffer {
        val grown = ByteBuffer.allocate(buffer.position() + minimumFree)
        buffer.flip()
        grown.put(buffer)
        return grown
    }

    private companion object {
        val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
    }
}
