package com.newoether.agora.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins EmailAccountSettings' pure boundaries: account serialization round-trips
 * without ever carrying a password field, the Gmail Sent-copy skip, and the
 * default poll interval. SecretCrypto's Keystore behavior is Android-only and
 * its plaintext pass-through is documented in SecretCrypto itself.
 */
class EmailAccountSettingsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `emailAccount serialization round-trips without the password field`() {
        val account = EmailAccount(
            id = "acc-1",
            email = "user@gmail.com",
            imapHost = "imap.gmail.com",
            smtpHost = "smtp.gmail.com",
        )
        val encoded = json.encodeToString(listOf(account))
        val decoded = json.decodeFromString<List<EmailAccount>>(encoded)

        assertEquals(1, decoded.size)
        assertEquals(account, decoded.first())
        // The account record is password-free by construction; the encoded JSON
        // must not contain a password key at all.
        assertTrue(!encoded.contains("password", ignoreCase = true))
    }

    @Test
    fun `gmail accounts skip the IMAP sent copy`() {
        assertTrue(EmailAccount.skipsSentCopy("a@gmail.com"))
        assertTrue(EmailAccount.skipsSentCopy("a@GMAIL.COM"))
        assertTrue(EmailAccount.skipsSentCopy("a@googlemail.com"))
        assertTrue(!EmailAccount.skipsSentCopy("a@fastmail.com"))
        assertTrue(!EmailAccount.skipsSentCopy("a@gmail.com.evil.tld"))
    }

    @Test
    fun `default poll interval is 15 minutes`() {
        assertEquals(15, DEFAULT_EMAIL_POLL_INTERVAL_MINUTES)
    }
}
