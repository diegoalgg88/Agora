package com.newoether.agora.data

import kotlinx.serialization.Serializable

/**
 * A connected email account. The password is deliberately absent — it lives
 * separately in the encrypted passwords map (SettingsManager), so account lists
 * can be logged, exported, or shown in the UI without exposing credentials.
 */
@Serializable
data class EmailAccount(
    val id: String,
    val email: String,
    val imapHost: String,
    val imapPort: Int = 993,
    val smtpHost: String,
    val smtpPort: Int = 587,
    val useStartTls: Boolean = true,
    val sentFolder: String? = null,
) {
    companion object {
        /** Gmail SMTP auto-saves to Sent, so the IMAP copy is skipped for it. */
        fun skipsSentCopy(email: String): Boolean =
            email.substringAfter("@").lowercase() in setOf("gmail.com", "googlemail.com")
    }
}
