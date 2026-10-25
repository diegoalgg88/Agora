package com.newoether.agora.api

import java.io.IOException
import okio.BufferedSource
import okio.Buffer
import okio.ByteString.Companion.toByteString

private val CR = '\r'.code.toByte()
private val LF = '\n'.code.toByte()
private val CRLF = byteArrayOf(CR, LF).toByteString()

/**
 * Lee una línea completa (acepta \n y \r\n) rechazando las que excedan [maxBytes]: un server que
 * envíe una línea sin fin no puede hacer crecer el buffer sin límite. Null al EOF vacío — la
 * misma semántica de readUtf8Line; una línea parcial al EOF se devuelve sin terminador.
 */
internal fun BufferedSource.readBoundedWireLine(maxBytes: Long): String? {
    require(maxBytes >= 0)
    val index = indexOfElement(CRLF)
    if (index == -1L) {
        // indexOfElement ya drenó el source: lo buffered es la línea final (o EOF vacío).
        if (buffer.size > maxBytes) throw IOException("Wire line exceeds $maxBytes bytes")
        return if (buffer.size == 0L) null else readUtf8(buffer.size)
    }
    if (index > maxBytes) throw IOException("Wire line exceeds $maxBytes bytes")
    val line = readUtf8(index)
    // Consume el terminador; un \r solo al EOF también cierra la línea, como readUtf8Line.
    if (buffer[0] == CR) {
        skip(1)
        if (request(1) && buffer[0] == LF) skip(1)
    } else {
        skip(1)
    }
    return line
}

/**
 * Lee el texto restante del body acotado a [maxBytes]: para payloads pequeños (handshakes,
 * frames de control) donde confiar en Content-Length no es necesario. Rechaza los cuerpos que
 * excedan el límite en vez de truncarlos silenciosamente.
 */
internal fun BufferedSource.readBoundedWireText(maxBytes: Long): String {
    require(maxBytes in 1..Int.MAX_VALUE.toLong())
    val out = Buffer()
    val chunk = Buffer()
    var total = 0L
    while (true) {
        val read = read(chunk, minOf(8192L, maxBytes + 1 - total))
        if (read == -1L) break
        total += read
        if (total > maxBytes) throw IOException("Wire text exceeds $maxBytes bytes")
        out.write(chunk, read)
    }
    return out.readUtf8()
}
