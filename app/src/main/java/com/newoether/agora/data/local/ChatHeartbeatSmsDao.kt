package com.newoether.agora.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for Heartbeat, SMS, Notification, and AssistantAction entities.
 *
 * Separated from ChatDao to keep file sizes under the 999-line limit.
 */
@Dao
interface ChatHeartbeatSmsDao {
    // ── Heartbeat DAO ────────────────────────────────────────

    @Query("SELECT * FROM heartbeat_logs ORDER BY timestampEpochMs DESC LIMIT 5")
    suspend fun getRecentHeartbeatLogs(): List<HeartbeatLogEntity>

    @Query("SELECT * FROM heartbeat_logs ORDER BY timestampEpochMs DESC LIMIT 5")
    fun getRecentHeartbeatLogsFlow(): Flow<List<HeartbeatLogEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHeartbeatLog(log: HeartbeatLogEntity)

    // ── SMS DAO ──────────────────────────────────────────────

    @Query("SELECT * FROM sms_messages WHERE id = :id")
    suspend fun getSmsMessageById(id: Long): SmsMessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSmsMessages(messages: List<SmsMessageEntity>)

    @Query("SELECT * FROM sms_sync_state WHERE id = 0")
    suspend fun getSmsSyncState(): SmsSyncStateEntity?

    @Query("SELECT * FROM sms_sync_state WHERE id = 0")
    fun getSmsSyncStateFlow(): Flow<SmsSyncStateEntity?>

    @Upsert
    suspend fun upsertSmsSyncState(state: SmsSyncStateEntity)

    @Query("SELECT * FROM sms_drafts ORDER BY createdAtEpochMs DESC LIMIT 20")
    fun getSmsDraftsFlow(): Flow<List<SmsDraftEntity>>

    @Upsert
    suspend fun upsertSmsDraft(draft: SmsDraftEntity)

    @Query("UPDATE sms_drafts SET status = :status, lastError = :error WHERE id = :id")
    suspend fun updateSmsDraftStatus(id: String, status: SmsDraftStatus, error: String?)

    @Query("DELETE FROM sms_drafts WHERE id NOT IN (SELECT id FROM sms_drafts ORDER BY createdAtEpochMs DESC LIMIT :cap)")
    suspend fun deleteSmsDraftsBeyondCap(cap: Int): Int

    @Query("DELETE FROM sms_drafts WHERE id = :id")
    suspend fun deleteSmsDraft(id: String): Int

    @Query("DELETE FROM sms_drafts WHERE status != 'PENDING' AND createdAtEpochMs < :cutoff")
    suspend fun cleanupOldSmsDrafts(cutoff: Long): Int

    @Query("SELECT * FROM sms_pending ORDER BY id ASC")
    suspend fun getPendingSms(): List<SmsPendingEntity>

    @Query("SELECT COUNT(*) FROM sms_pending")
    fun getPendingSmsCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingSms(messages: List<SmsPendingEntity>)

    @Query("DELETE FROM sms_pending WHERE id IN (:ids)")
    suspend fun deletePendingSms(ids: List<Long>): Int

    // ── Assistant pending actions DAO ────────────────────────

    @Query("SELECT * FROM assistant_actions ORDER BY createdAtEpochMs DESC LIMIT 20")
    fun getAssistantActionsFlow(): Flow<List<AssistantActionEntity>>

    @Upsert
    suspend fun upsertAssistantAction(action: AssistantActionEntity)

    @Query("UPDATE assistant_actions SET status = :status, lastError = :error WHERE id = :id")
    suspend fun updateAssistantActionStatus(id: String, status: AssistantActionStatus, error: String?)

    @Query("DELETE FROM assistant_actions WHERE id NOT IN (SELECT id FROM assistant_actions ORDER BY createdAtEpochMs DESC LIMIT :cap)")
    suspend fun deleteAssistantActionsBeyondCap(cap: Int): Int

    @Query("DELETE FROM assistant_actions WHERE id = :id")
    suspend fun deleteAssistantAction(id: String): Int

    @Query("DELETE FROM assistant_actions WHERE status != 'PENDING' AND createdAtEpochMs < :cutoff")
    suspend fun cleanupOldAssistantActions(cutoff: Long): Int

    // ── Notification DAO ─────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotifications(records: List<NotificationRecordEntity>)

    @Query("SELECT * FROM notifications WHERE id IN (:keys)")
    suspend fun getNotificationsByIds(keys: List<String>): List<NotificationRecordEntity>

    @Query("SELECT * FROM notifications WHERE id = :key")
    suspend fun getNotificationById(key: String): NotificationRecordEntity?

    @Query("SELECT * FROM notifications WHERE package_name = :packageName AND (title LIKE :query OR text LIKE :query) ORDER BY posted_at DESC LIMIT :limit")
    suspend fun searchNotificationsByPackage(query: String, packageName: String, limit: Int): List<NotificationRecordEntity>

    @Query("SELECT * FROM notifications WHERE title LIKE :query OR text LIKE :query ORDER BY posted_at DESC LIMIT :limit")
    suspend fun searchNotifications(query: String, limit: Int): List<NotificationRecordEntity>

    @Query("SELECT DISTINCT package_name FROM notifications")
    suspend fun getNotificationPackages(): List<String>

    @Query("SELECT * FROM notifications ORDER BY posted_at DESC LIMIT :limit")
    suspend fun getRecentNotifications(limit: Int): List<NotificationRecordEntity>

    @Query("DELETE FROM notifications WHERE posted_at < :cutoff")
    suspend fun deleteNotificationsOlderThan(cutoff: Long): Int

    @Query("""
        DELETE FROM notifications
        WHERE package_name = :packageName
          AND posted_at NOT IN (
              SELECT posted_at FROM notifications
              WHERE package_name = :packageName
              ORDER BY posted_at DESC
              LIMIT :cap
          )
        """)
    suspend fun deleteOldestNotificationsForPackage(packageName: String, cap: Int): Int

    @Query("SELECT * FROM notification_sync_state WHERE id = 0")
    suspend fun getNotificationSyncState(): NotificationSyncStateEntity?

    @Upsert
    suspend fun upsertNotificationSyncState(state: NotificationSyncStateEntity)

    // ── Task confirmations DAO (v38) ──────────────────────────
    // Durable owner of rich task confirmations. See TaskConfirmationEntity for the
    // table invariants and TaskConfirmationStore for the ownership policy.

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTaskConfirmation(row: TaskConfirmationEntity): Long

    @Query("SELECT * FROM task_confirmations WHERE id = :id")
    suspend fun getTaskConfirmation(id: String): TaskConfirmationEntity?

    @Query(
        """
        SELECT * FROM task_confirmations
        WHERE sourceType = :sourceType
          AND conversationId = :conversationId
          AND (
              (modelMessageId IS NULL AND :modelMessageId IS NULL)
              OR modelMessageId = :modelMessageId
          )
        LIMIT 1
        """,
    )
    suspend fun findTaskConfirmationByDedupKey(
        sourceType: String,
        conversationId: String,
        modelMessageId: String?,
    ): TaskConfirmationEntity?

    @Query(
        """
        SELECT * FROM task_confirmations
        WHERE status = 'PENDING'
        ORDER BY COALESCE(remindAtEpochMs, createdAtEpochMs) DESC, id ASC
        """,
    )
    fun observePendingTaskConfirmations(): Flow<List<TaskConfirmationEntity>>

    @Query(
        """
        SELECT * FROM task_confirmations
        WHERE status = 'PENDING'
          AND remindAtEpochMs IS NOT NULL
          AND remindAtEpochMs <= :now
        ORDER BY remindAtEpochMs ASC, id ASC
        """,
    )
    suspend fun selectDueTaskConfirmations(now: Long): List<TaskConfirmationEntity>

    /**
     * PENDING rows with NO armed snooze. Used by BootReceiver to re-arm the deferred
     * re-post alarms: `armedReminders()` filters on `remindAtEpochMs IS NOT NULL`, and
     * the P2 retry-post contract deliberately never touches that field — so without this
     * complementary query every pending retry-post alarm silently died on reboot.
     */
    @Query(
        """
        SELECT * FROM task_confirmations
        WHERE status = 'PENDING'
          AND remindAtEpochMs IS NULL
        ORDER BY createdAtEpochMs ASC, id ASC
        """,
    )
    suspend fun selectPendingWithoutSnooze(): List<TaskConfirmationEntity>

    /** Clears the snooze deadline only while the row is still PENDING; returns rows updated. */
    @Query(
        """
        UPDATE task_confirmations
        SET remindAtEpochMs = NULL
        WHERE id = :id AND status = 'PENDING'
        """,
    )
    suspend fun clearTaskConfirmationReminderIfStillPending(id: String): Int

    /** Arms a snooze deadline. Only called by the store's snooze() inside a transaction. */
    @Query(
        """
        UPDATE task_confirmations
        SET remindAtEpochMs = :remindAtEpochMs
        WHERE id = :id AND status = 'PENDING'
        """,
    )
    suspend fun armTaskConfirmationReminder(id: String, remindAtEpochMs: Long): Int

    /** Single-winner status transition; returns 0 when the row was no longer [fromStatus]. */
    @Query(
        """
        UPDATE task_confirmations
        SET status = :toStatus
        WHERE id = :id AND status = :fromStatus
        """,
    )
    suspend fun transitionTaskConfirmation(id: String, fromStatus: String, toStatus: String): Int

    /**
     * Caps only PENDING rows. Resolved rows are dedup tombstones bounded by the 7-day cleanup;
     * counting them toward the cap would let freshly resolved rows evict older unresolved ones.
     */
    @Query(
        """
        DELETE FROM task_confirmations
        WHERE status = 'PENDING'
          AND id NOT IN (
            SELECT id FROM task_confirmations
            WHERE status = 'PENDING'
            ORDER BY createdAtEpochMs DESC, id DESC
            LIMIT :cap
        )
        """,
    )
    suspend fun deleteTaskConfirmationsBeyondCap(cap: Int): Int

    @Query("DELETE FROM task_confirmations WHERE createdAtEpochMs < :cutoff")
    suspend fun cleanupTaskConfirmations(cutoff: Long): Int
}