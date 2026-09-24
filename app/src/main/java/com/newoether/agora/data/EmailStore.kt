package com.newoether.agora.data

import androidx.room.withTransaction
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.EmailMessageEntity
import com.newoether.agora.data.local.EmailPendingEntity
import com.newoether.agora.data.local.EmailSyncStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Store for email messages, pending queue, and per-account sync state.
 *
 * Room is the single durable truth: messages, the heartbeat pending queue, and
 * the per-account UID watermark all live in Room (no DataStore mirror, so the
 * two can never drift). IMAP remains the source for full bodies and search —
 * Room holds the polled preview projection plus the last fetched full body.
 */
class EmailStore(
    private val chatDao: ChatDao,
    private val database: ChatDatabase,
) {

    /** Per-account sync state (UID watermark, timestamps, error). Null row = never seeded. */
    fun syncState(accountId: String): Flow<EmailSyncState> =
        chatDao.getEmailSyncStateFlow(accountId).map { it?.toData() ?: EmailSyncState() }

    /** Number of unread messages fetched on the last poll of this account. */
    fun unreadCount(accountId: String): Flow<Int> = syncState(accountId).map { it.unreadCount }

    /** Number of emails (all accounts) waiting for the next heartbeat to consume. */
    val pendingCount: Flow<Int> = chatDao.getPendingEmailCount()

    suspend fun getSyncStateOnce(accountId: String): EmailSyncState = withContext(Dispatchers.IO) {
        chatDao.getEmailSyncState(accountId)?.toData() ?: EmailSyncState()
    }

    suspend fun updateSyncState(accountId: String, state: EmailSyncState) = withContext(Dispatchers.IO) {
        chatDao.upsertEmailSyncState(state.toEntity(accountId))
    }

    /**
     * Persists a polled batch atomically: messages, pending queue entries, and the
     * advanced sync state in one transaction (invariante 4.3 — one durable boundary).
     * The account is explicit — the watermark is per-account, never global.
     */
    suspend fun saveMessages(accountId: String, messages: List<EmailMessageData>, updatedState: EmailSyncState) {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                if (messages.isNotEmpty()) {
                    chatDao.insertEmailMessages(messages.map { it.toEntity() })
                    chatDao.insertPendingEmails(messages.map { it.toPendingEntity() })
                }
                chatDao.upsertEmailSyncState(updatedState.toEntity(accountId))
            }
        }
    }

    /** Snapshot of the pending queue (all accounts) for the next heartbeat; no bodies. */
    suspend fun getPendingSnapshot(): List<EmailPendingData> = withContext(Dispatchers.IO) {
        chatDao.getPendingEmails().map { pending ->
            EmailPendingData(
                accountId = pending.accountId,
                uid = pending.uid,
                fromAddress = pending.fromAddress,
                subject = pending.subject,
                dateEpochMs = pending.dateEpochMs,
                preview = pending.preview,
                isRead = pending.isRead,
            )
        }
    }

    /**
     * Removes exactly the pending rows the heartbeat just showed the AI. Keys are the
     * composite (accountId, uid) pairs of the snapshot — a plain uid is never unique
     * across accounts.
     */
    suspend fun removePending(keys: List<Pair<String, Long>>) = withContext(Dispatchers.IO) {
        if (keys.isNotEmpty()) {
            chatDao.deletePendingEmails(keys.map { (accountId, uid) -> "$accountId:$uid" })
        }
    }

    /** Looks up a previously fetched email by account and UID (full body for read_email). */
    suspend fun getMessageByUid(accountId: String, uid: Long): EmailMessageEntity? =
        withContext(Dispatchers.IO) {
            chatDao.getEmailMessage(accountId, uid)
        }

    /** Durable removal of every stored row belonging to one account (account teardown). */
    suspend fun removeAccountData(accountId: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            chatDao.deletePendingEmailsForAccount(accountId)
            chatDao.deleteEmailMessagesForAccount(accountId)
            chatDao.deleteEmailSyncState(accountId)
        }
    }

    private fun EmailMessageData.toEntity() = EmailMessageEntity(
        accountId = accountId,
        uid = uid,
        fromAddress = fromAddress,
        toAddress = toAddress,
        subject = subject,
        dateEpochMs = dateEpochMs,
        preview = preview,
        body = body,
        messageId = messageId,
        isRead = isRead,
        listUnsubscribe = listUnsubscribe,
        listUnsubscribePost = listUnsubscribePost,
    )

    private fun EmailMessageData.toPendingEntity() = EmailPendingEntity(
        accountId = accountId,
        uid = uid,
        fromAddress = fromAddress,
        subject = subject,
        dateEpochMs = dateEpochMs,
        preview = preview,
        isRead = isRead,
    )

    private fun EmailSyncStateEntity.toData() = EmailSyncState(
        lastSeenUid = lastSeenUid,
        lastSyncEpochMs = lastSyncEpochMs,
        lastAttemptEpochMs = lastAttemptEpochMs,
        unreadCount = unreadCount,
        lastError = lastError,
    )

    private fun EmailSyncState.toEntity(accountId: String) = EmailSyncStateEntity(
        accountId = accountId,
        lastSeenUid = lastSeenUid,
        lastSyncEpochMs = lastSyncEpochMs,
        lastAttemptEpochMs = lastAttemptEpochMs,
        unreadCount = unreadCount,
        lastError = lastError,
    )
}

/** Per-account email sync state (UID watermark, poll bookkeeping, last error). */
data class EmailSyncState(
    val lastSeenUid: Long = 0L,
    val lastSyncEpochMs: Long = 0L,
    val lastAttemptEpochMs: Long = 0L,
    val unreadCount: Int = 0,
    val lastError: String? = null,
)

/** A fetched email as persisted in Room (full form). */
data class EmailMessageData(
    val accountId: String,
    val uid: Long,
    val fromAddress: String,
    val toAddress: String,
    val subject: String,
    val dateEpochMs: Long,
    val preview: String,
    val body: String,
    val messageId: String? = null,
    val isRead: Boolean = false,
    val listUnsubscribe: String? = null,
    val listUnsubscribePost: String? = null,
)

/** Pending-queue projection the heartbeat prompt renders (no body column read). */
data class EmailPendingData(
    val accountId: String,
    val uid: Long,
    val fromAddress: String,
    val subject: String,
    val dateEpochMs: Long,
    val preview: String,
    val isRead: Boolean = false,
)
