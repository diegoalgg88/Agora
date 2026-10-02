package com.newoether.agora.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for Email entities (messages, per-account sync state, pending queue, drafts).
 *
 * Separated from ChatDao to keep file sizes under the 999-line limit. Unlike the
 * SMS DAO, email is multi-account from the design: every query keys on
 * (accountId, uid) — a plain uid is never unique across accounts.
 */
@Dao
interface ChatEmailDao {
    // ── Email messages ───────────────────────────────────────

    @Query("SELECT * FROM email_messages WHERE accountId = :accountId AND uid = :uid")
    suspend fun getEmailMessage(accountId: String, uid: Long): EmailMessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEmailMessages(messages: List<EmailMessageEntity>)

    @Query("SELECT * FROM email_messages WHERE accountId = :accountId ORDER BY dateEpochMs DESC LIMIT :limit")
    suspend fun getRecentEmailMessages(accountId: String, limit: Int): List<EmailMessageEntity>

    @Query("SELECT * FROM email_messages WHERE accountId = :accountId AND (fromAddress LIKE :query OR subject LIKE :query) ORDER BY dateEpochMs DESC LIMIT :limit")
    suspend fun searchEmailMessages(accountId: String, query: String, limit: Int): List<EmailMessageEntity>

    @Query("DELETE FROM email_messages WHERE accountId = :accountId")
    suspend fun deleteEmailMessagesForAccount(accountId: String): Int

    // ── Email sync state (one row per account) ───────────────

    @Query("SELECT * FROM email_sync_state WHERE accountId = :accountId")
    suspend fun getEmailSyncState(accountId: String): EmailSyncStateEntity?

    @Query("SELECT * FROM email_sync_state WHERE accountId = :accountId")
    fun getEmailSyncStateFlow(accountId: String): Flow<EmailSyncStateEntity?>

    @Query("SELECT * FROM email_sync_state")
    suspend fun getAllEmailSyncStates(): List<EmailSyncStateEntity>

    @Upsert
    suspend fun upsertEmailSyncState(state: EmailSyncStateEntity)

    @Query("DELETE FROM email_sync_state WHERE accountId = :accountId")
    suspend fun deleteEmailSyncState(accountId: String): Int

    // ── Email pending queue (heartbeat delivery) ─────────────

    @Query("SELECT * FROM email_pending ORDER BY dateEpochMs ASC")
    suspend fun getPendingEmails(): List<EmailPendingEntity>

    @Query("SELECT COUNT(*) FROM email_pending")
    fun getPendingEmailCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingEmails(messages: List<EmailPendingEntity>)

    /**
     * Removes exactly the keys of the snapshot the AI has already seen. Composite keys are
     * encoded as "accountId:uid" so a single IN clause covers both key parts without
     * matching the same uid of a different account.
     */
    @Query("DELETE FROM email_pending WHERE accountId || ':' || uid IN (:keys)")
    suspend fun deletePendingEmails(keys: List<String>): Int

    @Query("DELETE FROM email_pending WHERE accountId = :accountId")
    suspend fun deletePendingEmailsForAccount(accountId: String): Int

    // ── Email drafts (approval banner) ────────────────────────

    @Query("SELECT * FROM email_drafts ORDER BY createdAtEpochMs DESC LIMIT 20")
    fun getEmailDraftsFlow(): Flow<List<EmailDraftEntity>>

    @Query("SELECT * FROM email_drafts WHERE id = :id")
    suspend fun getEmailDraft(id: String): EmailDraftEntity?

    @Upsert
    suspend fun upsertEmailDraft(draft: EmailDraftEntity)

    @Query("UPDATE email_drafts SET status = :status, lastError = :error WHERE id = :id")
    suspend fun updateEmailDraftStatus(id: String, status: EmailDraftStatus, error: String?)

    @Query("DELETE FROM email_drafts WHERE id NOT IN (SELECT id FROM email_drafts ORDER BY createdAtEpochMs DESC LIMIT :cap)")
    suspend fun deleteEmailDraftsBeyondCap(cap: Int): Int

    @Query("DELETE FROM email_drafts WHERE id = :id")
    suspend fun deleteEmailDraft(id: String): Int

    /**
     * Atomic dispatch claim: PENDING (first send) or FAILED (retry) -> SENDING in one
     * statement. Returns 1 only for the single caller that won the transition, so two
     * concurrent taps can never both reach SMTP. SENDING is deliberately not claimable.
     */
    @Query("UPDATE email_drafts SET status = 'SENDING', lastError = NULL WHERE id = :id AND status IN ('PENDING', 'FAILED')")
    suspend fun claimEmailDraftForSending(id: String): Int

    /** Marks drafts left in SENDING by a dead process as FAILED (outcome unknown). */
    @Query("UPDATE email_drafts SET status = 'FAILED', lastError = :reason WHERE status = 'SENDING'")
    suspend fun failInterruptedEmailDrafts(reason: String): Int

    @Query("DELETE FROM email_drafts WHERE accountId = :accountId")
    suspend fun deleteEmailDraftsForAccount(accountId: String): Int

    // ── Orphan reconciliation (rows of accounts that are no longer connected) ──
    // The *Outside variants cannot bind an empty collection (SQLite rejects `NOT IN ()`),
    // so the caller routes a genuinely empty connected list to the deleteAll variants —
    // an archive with zero accounts leaves every email row orphaned.

    @Query("DELETE FROM email_pending WHERE accountId NOT IN (:connectedIds)")
    suspend fun deletePendingEmailsOutside(connectedIds: List<String>): Int

    @Query("DELETE FROM email_messages WHERE accountId NOT IN (:connectedIds)")
    suspend fun deleteEmailMessagesOutside(connectedIds: List<String>): Int

    @Query("DELETE FROM email_sync_state WHERE accountId NOT IN (:connectedIds)")
    suspend fun deleteEmailSyncStatesOutside(connectedIds: List<String>): Int

    @Query("DELETE FROM email_drafts WHERE accountId NOT IN (:connectedIds)")
    suspend fun deleteEmailDraftsOutside(connectedIds: List<String>): Int

    @Query("DELETE FROM email_pending")
    suspend fun deleteAllPendingEmails(): Int

    @Query("DELETE FROM email_messages")
    suspend fun deleteAllEmailMessages(): Int

    @Query("DELETE FROM email_sync_state")
    suspend fun deleteAllEmailSyncStates(): Int

    @Query("DELETE FROM email_drafts")
    suspend fun deleteAllEmailDrafts(): Int

    @Query("DELETE FROM email_drafts WHERE status != 'PENDING' AND createdAtEpochMs < :cutoff")
    suspend fun cleanupOldEmailDrafts(cutoff: Long): Int
}
