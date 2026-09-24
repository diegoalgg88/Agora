package com.newoether.agora.data

import androidx.room.withTransaction
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.EmailDraftEntity
import com.newoether.agora.data.local.EmailDraftStatus
import com.newoether.agora.email.ImapClient
import com.newoether.agora.email.SmtpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Store for email drafts (outgoing messages awaiting user confirmation).
 *
 * Room is the single durable truth. The draft list is a Room flow; the cap (20) is
 * enforced by evicting the oldest rows atomically on insert. Sending is
 * orchestrated here: PENDING → SENDING → SENT/FAILED. The actual dispatch goes
 * through [SmtpClient] plus the IMAP Sent copy — never directly from the AI,
 * always from the user's tap in the review banner.
 *
 * Sent-copy resolution order: the account's configured folder → the server's
 * SPECIAL-USE \Sent (RFC 6154) → common folder names → CREATE as a last resort.
 * Gmail is skipped entirely (its SMTP already files outgoing mail into Sent).
 */
class EmailDraftStore(
    private val chatDao: ChatDao,
    private val database: ChatDatabase,
    private val accountResolver: suspend (String) -> EmailAccount?,
    private val passwordResolver: suspend (String) -> String?,
    private val smtpClientFactory: (EmailAccount) -> SmtpClient = { account ->
        SmtpClient(account.smtpHost, account.smtpPort, account.useStartTls)
    },
    private val imapClientFactory: (EmailAccount) -> ImapClient = { account ->
        ImapClient(account.imapHost, account.imapPort, tls = true)
    },
) {

    /** Current drafts, newest first, capped at 20. */
    val drafts: Flow<List<EmailDraft>> = chatDao.getEmailDraftsFlow().map { entities ->
        entities.map { it.toData() }
    }

    /** Adds a new draft; oldest drafts beyond the cap are evicted atomically. */
    suspend fun addDraft(draft: EmailDraft) = withContext(Dispatchers.IO) {
        database.withTransaction {
            chatDao.upsertEmailDraft(draft.toEntity())
            chatDao.deleteEmailDraftsBeyondCap(MAX_DRAFTS)
        }
    }

    /** Targeted status transition — no read-modify-write, so concurrent calls cannot race. */
    suspend fun updateStatus(draftId: String, status: EmailDraftStatus, error: String? = null) =
        withContext(Dispatchers.IO) {
            chatDao.updateEmailDraftStatus(draftId, status, error)
        }

    suspend fun removeDraft(draftId: String) = withContext(Dispatchers.IO) {
        chatDao.deleteEmailDraft(draftId)
    }

    /**
     * User-triggered send (banner Approve): flips the draft to SENDING, dispatches via
     * SMTP, files the Sent copy over IMAP, then records SENT or FAILED (with the
     * failure reason). Returns false if the draft is missing or no longer PENDING.
     */
    suspend fun sendDraft(draftId: String): Boolean {
        val draft = drafts.first().find { it.id == draftId } ?: return false
        if (draft.status != EmailDraftStatus.PENDING) return false
        val account = accountResolver(draft.accountId) ?: run {
            updateStatus(draftId, EmailDraftStatus.FAILED, "Account no longer connected")
            return false
        }
        val password = passwordResolver(draft.accountId) ?: run {
            updateStatus(draftId, EmailDraftStatus.FAILED, "Account credentials missing")
            return false
        }

        updateStatus(draftId, EmailDraftStatus.SENDING)
        try {
            val smtp = smtpClientFactory(account)
            smtp.connect()
            try {
                smtp.ehlo()
                smtp.startTls()
                smtp.authenticate(account.email, password)
                val raw = smtp.sendReply(
                    from = account.email,
                    to = draft.toAddress,
                    subject = draft.subject,
                    body = draft.body,
                    inReplyTo = draft.inReplyToMessageId,
                )
                if (raw == null) {
                    updateStatus(draftId, EmailDraftStatus.FAILED, "SMTP rejected the message")
                    return false
                }
                saveCopyToSentFolder(account, password, raw)
            } finally {
                smtp.quit()
            }
            updateStatus(draftId, EmailDraftStatus.SENT)
            return true
        } catch (error: Exception) {
            // Exception messages come from SmtpClient's server-response quotes only —
            // never from the sent command — so no credential can leak into Room here.
            updateStatus(draftId, EmailDraftStatus.FAILED, error.message ?: "Send failed")
            return false
        }
    }

    /**
     * Files the raw sent message into the account's Sent folder, resolving the
     * mailbox: configured name → SPECIAL-USE \Sent → common names → CREATE.
     * APPEND is attempted with the real message — a rejected mailbox returns
     * false with no side effects (TRYCREATE), so no empty message is ever filed.
     */
    private suspend fun saveCopyToSentFolder(account: EmailAccount, password: String, raw: String) {
        if (EmailAccount.skipsSentCopy(account.email)) return

        val imap = imapClientFactory(account)
        imap.connect()
        try {
            if (!imap.login(account.email, password)) return

            account.sentFolder?.let { configured ->
                if (imap.appendToMailbox(configured, raw)) return
            }
            imap.findSentMailbox()?.let { advertised ->
                if (imap.appendToMailbox(advertised, raw)) return
            }
            for (candidate in listOf("Sent", "Sent Messages", "Sent Items", "INBOX.Sent")) {
                if (imap.appendToMailbox(candidate, raw)) return
            }
            if (imap.createMailbox("Sent")) {
                imap.appendToMailbox("Sent", raw)
            }
        } finally {
            imap.logout()
        }
    }

    /** Removes non-pending drafts older than [cutoffEpochMs] (7 days). */
    suspend fun cleanupOldDrafts(cutoffEpochMs: Long = System.currentTimeMillis() - CLEANUP_AGE_MS) =
        withContext(Dispatchers.IO) {
            chatDao.cleanupOldEmailDrafts(cutoffEpochMs)
        }

    private fun EmailDraft.toEntity() = EmailDraftEntity(
        id = id,
        accountId = accountId,
        toAddress = toAddress,
        subject = subject,
        body = body,
        createdAtEpochMs = createdAtEpochMs,
        inReplyToMessageId = inReplyToMessageId,
        status = status,
        lastError = lastError,
    )

    private fun EmailDraftEntity.toData() = EmailDraft(
        id = id,
        accountId = accountId,
        toAddress = toAddress,
        subject = subject,
        body = body,
        createdAtEpochMs = createdAtEpochMs,
        inReplyToMessageId = inReplyToMessageId,
        status = status,
        lastError = lastError,
    )

    companion object {
        private const val MAX_DRAFTS = 20
        private const val CLEANUP_AGE_MS = 7 * 24 * 60 * 60 * 1000L
    }
}

/**
 * An outgoing email the AI has staged. Nothing leaves the device until the user
 * taps Approve in the review banner — the existence of a draft is the defensive
 * gate between AI intent and real-world action, mirroring [SmsDraft].
 */
data class EmailDraft(
    val id: String = java.util.UUID.randomUUID().toString(),
    val accountId: String,
    val toAddress: String,
    val subject: String,
    val body: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val inReplyToMessageId: String? = null,
    val status: EmailDraftStatus = EmailDraftStatus.PENDING,
    val lastError: String? = null,
)
