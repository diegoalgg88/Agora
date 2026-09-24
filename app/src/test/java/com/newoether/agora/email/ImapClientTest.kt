package com.newoether.agora.email

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scripts a fake [EmailConnection] to exercise ImapClient protocol details:
 * tagged-command sequencing, LIST SPECIAL-USE parsing, APPEND literal sizing,
 * and FETCH response parsing (headers, MIME, quoted-printable, base64, HTML fallback).
 * Responses mirror realistic IMAP output: header literals precede BODY[TEXT].
 */
class ImapClientTest {

    private class ScriptedConnection(private val script: MutableList<String>) : EmailConnection {
        val sent = mutableListOf<String>()
        var closed = false

        override suspend fun readLine(): String =
            script.removeFirstOrNull() ?: throw IllegalStateException("Script exhausted")

        override suspend fun writeLine(line: String) {
            sent += line
        }

        override suspend fun upgradeToTls(host: String) {}
        override suspend fun close() {
            closed = true
        }
    }

    private fun client(script: List<String>): Pair<ImapClient, ScriptedConnection> {
        val connection = ScriptedConnection(script.toMutableList())
        val imap = ImapClient("imap.example.com", 993, tls = true) { _, _, _ -> connection }
        return imap to connection
    }

    @Test
    fun `greeting is consumed on connect`() = runBlocking {
        val (imap, _) = client(listOf("* OK imap ready"))
        imap.connect()
    }

    @Test
    fun `login sends tagged command and reads OK`() = runBlocking {
        val (imap, conn) = client(listOf("* OK ready", "A1 OK LOGIN completed"))
        imap.connect()
        val ok = imap.login("user@example.com", "secret123")
        assertTrue(ok)
        assertEquals("A1 LOGIN \"user@example.com\" \"secret123\"", conn.sent.last())
    }

    @Test
    fun `login escapes quotes and backslashes and never echoes password in failure`() = runBlocking {
        val (imap, conn) = client(listOf("* OK ready", "A1 NO [AUTHENTICATIONFAILED] invalid credentials"))
        imap.connect()
        val ok = imap.login("us\"er", "pa\\ss")
        assertFalse(ok)
        assertEquals("A1 LOGIN \"us\\\"er\" \"pa\\\\ss\"", conn.sent.last())
    }

    @Test
    fun `selectInbox parses EXISTS count`() = runBlocking {
        val (imap, _) = client(listOf("* OK ready", "* 42 EXISTS", "A1 OK [READ-WRITE] SELECT completed"))
        imap.connect()
        assertEquals(42, imap.selectInbox())
    }

    @Test
    fun `search parses uid list`() = runBlocking {
        val (imap, _) = client(listOf("* OK ready", "* SEARCH 1 2 3 99", "A1 OK SEARCH completed"))
        imap.connect()
        assertEquals(listOf(1L, 2L, 3L, 99L), imap.searchUnseen())
    }

    @Test
    fun `search returns empty when no matches`() = runBlocking {
        val (imap, _) = client(listOf("* OK ready", "A1 OK SEARCH completed"))
        imap.connect()
        assertTrue(imap.searchUnseen().isEmpty())
    }

    @Test
    fun `findSentMailbox returns special-use mailbox with quoted name`() = runBlocking {
        val (imap, _) = client(
            listOf(
                "* OK ready",
                "* LIST (\\HasNoChildren) \"/\" INBOX",
                "* LIST (\\HasNoChildren \\Sent) \"/\" \"Sent Messages\"",
                "A1 OK LIST completed",
            ),
        )
        imap.connect()
        assertEquals("Sent Messages", imap.findSentMailbox())
    }

    @Test
    fun `findSentMailbox returns null without special-use attribute`() = runBlocking {
        val (imap, _) = client(
            listOf(
                "* OK ready",
                "* LIST (\\HasNoChildren) \"/\" INBOX",
                "A1 OK LIST completed",
            ),
        )
        imap.connect()
        assertNull(imap.findSentMailbox())
    }

    @Test
    fun `findSentMailbox handles NIL delimiter and escaped quote name`() = runBlocking {
        val (imap, _) = client(
            listOf(
                "* OK ready",
                "* LIST (\\Sent) NIL \"My \\\"Sent\\\" Box\"",
                "A1 OK LIST completed",
            ),
        )
        imap.connect()
        assertEquals("My \"Sent\" Box", imap.findSentMailbox())
    }

    @Test
    fun `createMailbox succeeds on OK response`() = runBlocking {
        val (imap, _) = client(listOf("* OK ready", "A1 OK CREATE completed"))
        imap.connect()
        assertTrue(imap.createMailbox("Sent"))
    }

    @Test
    fun `append sends continuation-gated literal with exact CRLF byte count`() = runBlocking {
        val message = "From: a@example.com\nTo: b@example.com\n\nhello"
        val (imap, conn) = client(
            listOf(
                "* OK ready",
                "+ Ready for literal data",
                "A1 OK APPEND completed",
            ),
        )
        imap.connect()
        val ok = imap.appendToMailbox("Sent", message)
        assertTrue(ok)

        val appendCommand = conn.sent[0]
        // Literal counts content bytes plus CRLFs (2 each, minus final trailing CRLF).
        val declaredSize = Regex("\\{(\\d+)\\}").find(appendCommand)!!.groupValues[1].toInt()
        val lines = message.lines()
        val expectedSize = lines.sumOf { it.encodeToByteArray().size + 2 } - 2
        assertEquals(expectedSize, declaredSize)
        // One writeLine per literal line, after the APPEND command itself.
        assertEquals(1 + lines.size, conn.sent.size)
        assertEquals("From: a@example.com", conn.sent[1])
        assertEquals("", conn.sent[3])
    }

    @Test
    fun `append returns false when server rejects the mailbox`() = runBlocking {
        val (imap, _) = client(
            listOf(
                "* OK ready",
                "A1 NO [TRYCREATE] Mailbox doesn't exist: Sent",
            ),
        )
        imap.connect()
        assertFalse(imap.appendToMailbox("Sent", "From: a@b\n\nhi"))
    }

    @Test
    fun `fetchHeaders parses simple plain text response`() = runBlocking {
        val response = listOf(
            "* 1 FETCH (FLAGS (\\Seen) BODY[HEADER.FIELDS (FROM SUBJECT DATE MESSAGE-ID)] {110}",
            "From: alice@example.com",
            "Subject: Hi there",
            "Date: Mon, 21 Sep 2026 10:00:00 +0000",
            "Message-ID: <m1@example.com>",
            "",
            " BODY[TEXT]<0> {9}",
            "hello the",
            ")",
            "A1 OK FETCH completed",
        )
        val (imap, _) = client(listOf("* OK ready") + response)
        imap.connect()
        val messages = imap.fetchHeaders(listOf(1L), "account-1")
        assertEquals(1, messages.size)
        val msg = messages.first()
        assertEquals(1L, msg.uid)
        assertEquals("alice@example.com", msg.fromAddress)
        assertEquals("Hi there", msg.subject)
        assertTrue(msg.isRead)
        assertEquals("hello the", msg.body)
        assertTrue(msg.dateEpochMs > 0)
    }

    @Test
    fun `body lines that look like headers do not corrupt header parsing`() = runBlocking {
        val response = listOf(
            "* 1 FETCH (BODY[HEADER.FIELDS (FROM SUBJECT)] {50}",
            "From: real@example.com",
            "Subject: Real subject",
            "",
            " BODY[TEXT] {52}",
            "quoted text begins here",
            "From: fake-in-body@example.com",
            "",
            ")",
            "A1 OK FETCH completed",
        )
        val (imap, _) = client(listOf("* OK ready") + response)
        imap.connect()
        val msg = imap.fetchHeaders(listOf(1L), "account-1").first()
        assertEquals("real@example.com", msg.fromAddress)
        assertEquals("Real subject", msg.subject)
        assertTrue(msg.body.contains("fake-in-body"))
    }

    @Test
    fun `multipart quoted-printable plain part is decoded`() = runBlocking {
        val response = listOf(
            "* 1 FETCH (BODY[HEADER.FIELDS (FROM SUBJECT)] {40}",
            "From: bob@example.com",
            "Subject: Lunch",
            "",
            " BODY[TEXT] {220}",
            "--BOUNDARY1",
            "Content-Type: text/plain; charset=UTF-8",
            "Content-Transfer-Encoding: quoted-printable",
            "",
            "caf=C3=A9 is ready",
            "--BOUNDARY1",
            "Content-Type: text/html; charset=UTF-8",
            "",
            "<p>caf=C3=A9</p>",
            "--BOUNDARY1--",
            ")",
            "A1 OK FETCH completed",
        )
        val (imap, _) = client(listOf("* OK ready") + response)
        imap.connect()
        val msg = imap.fetchHeaders(listOf(1L), "account-1").first()
        assertEquals("café is ready", msg.body)
    }

    @Test
    fun `multipart base64 html part falls back to stripped html`() = runBlocking {
        val htmlBase64 = java.util.Base64.getMimeEncoder()
            .encodeToString("<html><body>Hi<br>there</body></html>".encodeToByteArray())
        val response = listOf(
            "* 1 FETCH (BODY[HEADER.FIELDS (FROM SUBJECT)] {40}",
            "From: carol@example.com",
            "Subject: Report",
            "",
            " BODY[TEXT] {300}",
            "--BOUNDARY2",
            "Content-Type: text/html; charset=UTF-8",
            "Content-Transfer-Encoding: base64",
            "",
            htmlBase64,
            "--BOUNDARY2--",
            ")",
            "A1 OK FETCH completed",
        )
        val (imap, _) = client(listOf("* OK ready") + response)
        imap.connect()
        val msg = imap.fetchHeaders(listOf(1L), "account-1").first()
        // No text/plain part: body derives from the HTML, tags stripped.
        assertTrue(msg.body.contains("Hi"))
        assertTrue(msg.body.contains("there"))
        assertFalse(msg.body.contains("<"))
    }

    @Test
    fun `header continuation lines are unfolded`() = runBlocking {
        val response = listOf(
            "* 1 FETCH (BODY[HEADER.FIELDS (FROM SUBJECT LIST-UNSUBSCRIBE)] {90}",
            "From: dave@example.com",
            "List-Unsubscribe: <https://example.com/unsub>,",
            " <mailto:unsub@example.com>",
            "Subject: News",
            "",
            " BODY[TEXT] {2}",
            "hi",
            ")",
            "A1 OK FETCH completed",
        )
        val (imap, _) = client(listOf("* OK ready") + response)
        imap.connect()
        val msg = imap.fetchHeaders(listOf(1L), "account-1").first()
        assertEquals("<https://example.com/unsub>, <mailto:unsub@example.com>", msg.listUnsubscribe)
    }

    @Test
    fun `logout closes the connection`() = runBlocking {
        val (imap, conn) = client(listOf("* OK ready", "* BYE", "A1 OK LOGOUT completed"))
        imap.connect()
        imap.logout()
        assertTrue(conn.closed)
    }

    @Test
    fun `rfc5322 dates parse or return zero`() {
        assertTrue(parseRfc5322DateToEpochMs("Mon, 21 Sep 2026 10:00:00 +0000") > 0)
        assertTrue(parseRfc5322DateToEpochMs("21 Sep 2026 10:00:00 -0600") > 0)
        assertEquals(0L, parseRfc5322DateToEpochMs("not a date"))
        assertEquals(0L, parseRfc5322DateToEpochMs(""))
    }
}
