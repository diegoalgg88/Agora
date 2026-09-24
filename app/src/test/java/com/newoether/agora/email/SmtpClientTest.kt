package com.newoether.agora.email

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneOffset

/**
 * Scripts a fake [EmailConnection] to exercise SmtpClient protocol details:
 * EHLO/STARTTLS re-handshake sequencing, AUTH LOGIN base64 exchange,
 * dot-stuffing, threading headers, and ServerAutoDetect's domain map.
 */
class SmtpClientTest {

    private class ScriptedConnection(private val script: MutableList<String>) : EmailConnection {
        val sent = mutableListOf<String>()
        var tlsUpgraded = false
        var closed = false

        override suspend fun readLine(): String =
            script.removeFirstOrNull() ?: throw IllegalStateException("Script exhausted")

        override suspend fun writeLine(line: String) {
            sent += line
        }

        override suspend fun upgradeToTls(host: String) {
            tlsUpgraded = true
        }

        override suspend fun close() {
            closed = true
        }
    }

    private fun client(
        useStartTls: Boolean = true,
        script: List<String>,
    ): Pair<SmtpClient, ScriptedConnection> {
        val connection = ScriptedConnection(script.toMutableList())
        val smtp = SmtpClient("smtp.example.com", 587, useStartTls) { _, _, _ -> connection }
        return smtp to connection
    }

    @Test
    fun `full starttls flow re-issues ehlo after upgrade`() = runBlocking {
        val (smtp, conn) = client(
            script = listOf(
                "220 smtp.example.com ESMTP ready",
                "250-smtp.example.com",
                "250 PIPELINING",
                "220 Ready to start TLS",
                "250-smtp.example.com",
                "250 AUTH LOGIN",
                "334 VXNlcm5hbWU6",
                "334 UGFzc3dvcmQ6",
                "235 Authentication successful",
                "250 OK",
                "250 OK",
                "354 End data with <CR><LF>.<CR><LF>",
                "250 OK queued",
                "221 Bye",
            ),
        )
        smtp.connect()
        smtp.ehlo()
        smtp.startTls()
        smtp.authenticate("user@example.com", "secret123")
        val raw = smtp.sendReply("user@example.com", "dest@example.com", "Hi", "Hello there")
        smtp.quit()

        assertTrue(conn.tlsUpgraded)
        assertEquals("EHLO localhost", conn.sent[0])
        assertEquals("STARTTLS", conn.sent[1])
        // Second EHLO issued after the TLS upgrade, unqualified.
        assertEquals("EHLO localhost", conn.sent[2])
        assertEquals("AUTH LOGIN", conn.sent[3])
        assertEquals("dXNlckBleGFtcGxlLmNvbQ==", conn.sent[4])
        assertEquals("c2VjcmV0MTIz", conn.sent[5])
        assertEquals("MAIL FROM:<user@example.com>", conn.sent[6])
        assertEquals("RCPT TO:<dest@example.com>", conn.sent[7])
        assertEquals("DATA", conn.sent[8])

        // Raw message returned for the Sent copy: headers include threading
        // only when inReplyTo is provided — this call has none.
        assertTrue(raw != null)
        assertTrue(raw!!.contains("From: user@example.com"))
        assertTrue(raw.contains("Subject: Hi"))
        assertTrue(raw.contains("Content-Type: text/plain; charset=UTF-8"))
        assertFalse(raw.contains("In-Reply-To:"))

        // Dot terminator ends the DATA phase (QUIT follows it).
        assertEquals(".", conn.sent[conn.sent.size - 2])
        assertEquals("QUIT", conn.sent.last())
    }

    @Test
    fun `reply includes in-reply-to and references headers`() = runBlocking {
        val (smtp, _) = client(
            useStartTls = false,
            script = listOf(
                "220 ready",
                "250 OK",
                "334 VXNlcm5hbWU6",
                "334 UGFzc3dvcmQ6",
                "235 ok",
                "250 OK",
                "250 OK",
                "354 go",
                "250 queued",
            ),
        )
        smtp.connect()
        smtp.ehlo()
        smtp.authenticate("a@b.com", "pw")
        val raw = smtp.sendReply(
            "a@b.com",
            "c@d.com",
            "Re: Hello",
            "Reply body",
            inReplyTo = "<orig@d.com>",
        )
        assertTrue(raw!!.contains("In-Reply-To: <orig@d.com>"))
        assertTrue(raw.contains("References: <orig@d.com>"))
    }

    @Test
    fun `body lines starting with a dot are dot-stuffed`() = runBlocking {
        val (smtp, conn) = client(
            useStartTls = false,
            script = listOf(
                "220 ready",
                "250 OK",
                "334 VXNlcm5hbWU6",
                "334 UGFzc3dvcmQ6",
                "235 ok",
                "250 OK",
                "250 OK",
                "354 go",
                "250 queued",
            ),
        )
        smtp.connect()
        smtp.ehlo()
        smtp.authenticate("a@b.com", "pw")
        smtp.sendReply("a@b.com", "c@d.com", "S", ".leading dot\nsecond line")
        // The literal "." alone would terminate DATA; ".leading dot" must arrive stuffed.
        assertTrue(conn.sent.contains("..leading dot"))
        assertFalse(conn.sent.dropLast(1).contains("."))
    }

    @Test
    fun `authentication failure quotes only the server response`() = runBlocking {
        val (smtp, _) = client(
            useStartTls = false,
            script = listOf(
                "220 ready",
                "250 OK",
                "334 VXNlcm5hbWU6",
                "334 UGFzc3dvcmQ6",
                "535 Authentication credentials invalid",
            ),
        )
        smtp.connect()
        smtp.ehlo()
        val error = runCatching { smtp.authenticate("user@x.com", "TOP-SECRET-PW") }.exceptionOrNull()
        assertTrue(error is Exception)
        // The exception text carries the server line but never the base64 password.
        assertTrue(error!!.message!!.contains("535 Authentication credentials invalid"))
        assertFalse(error.message!!.contains("TOP-SECRET-PW"))
    }

    @Test
    fun `send returns null when server rejects at end of data`() = runBlocking {
        val (smtp, _) = client(
            useStartTls = false,
            script = listOf(
                "220 ready",
                "250 OK",
                "334 VXNlcm5hbWU6",
                "334 UGFzc3dvcmQ6",
                "235 ok",
                "250 OK",
                "250 OK",
                "354 go",
                "554 Message rejected",
            ),
        )
        smtp.connect()
        smtp.ehlo()
        smtp.authenticate("a@b.com", "pw")
        assertNull(smtp.sendReply("a@b.com", "c@d.com", "S", "body"))
    }

    @Test
    fun `multiline responses are read until the space-terminated line`() {
        val (smtp, conn) = client(
            useStartTls = false,
            script = listOf(
                "220 ready",
                "250-first line",
                "250-second line",
                "250 final line",
            ),
        )
        runBlocking {
            smtp.connect()
            smtp.ehlo()
        }
        // Multiline EHLO consumed exactly the four scripted greeting/EHLO lines.
        assertEquals("EHLO localhost", conn.sent.last())
    }

    @Test
    fun `rfc5322Date formats day name, offset and no leading-zero day`() {
        val now = ZonedDateTime.of(2026, 9, 7, 9, 5, 3, 0, ZoneOffset.of("-0600"))
        val formatted = rfc5322Date(now)
        assertEquals("Mon, 7 Sep 2026 09:05:03 -06:00", formatted)
    }

    @Test
    fun `rfc5322Date pads single-digit hours minutes seconds`() {
        val now = ZonedDateTime.of(2026, 12, 25, 1, 2, 3, 0, ZoneOffset.UTC)
        assertEquals("Fri, 25 Dec 2026 01:02:03 Z", rfc5322Date(now))
    }

    @Test
    fun `serverAutoDetect maps known providers and excludes proton`() {
        val gmail = ServerAutoDetect.detect("user@gmail.com")!!
        assertEquals("imap.gmail.com", gmail.imapHost)
        assertEquals("smtp.gmail.com", gmail.smtpHost)
        assertTrue(gmail.note.contains("App Password"))

        assertEquals("outlook.office365.com", ServerAutoDetect.detect("a@hotmail.com")!!.imapHost)
        assertEquals("imap.mail.yahoo.com", ServerAutoDetect.detect("a@yahoo.com")!!.imapHost)
        assertEquals("imap.mail.me.com", ServerAutoDetect.detect("a@icloud.com")!!.imapHost)
        assertEquals("imap.zoho.com", ServerAutoDetect.detect("a@zoho.com")!!.imapHost)
        assertEquals(465, ServerAutoDetect.detect("a@zoho.com")!!.smtpPort)
        assertEquals("imap.fastmail.com", ServerAutoDetect.detect("a@fastmail.com")!!.imapHost)

        // Case-insensitive domain match.
        assertEquals(gmail.imapHost, ServerAutoDetect.detect("a@GMAIL.COM")!!.imapHost)

        assertNull(ServerAutoDetect.detect("a@proton.me"))
        assertNull(ServerAutoDetect.detect("a@protonmail.com"))
        assertNull(ServerAutoDetect.detect("a@unknown.example"))
    }
}
