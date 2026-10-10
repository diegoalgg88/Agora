package com.newoether.agora.data

import androidx.room.withTransaction
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.TaskConfirmationEntity
import com.newoether.agora.data.local.TaskConfirmationSource
import com.newoether.agora.data.local.TaskConfirmationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Durable owner of rich task confirmations (plan PLAN-20261002-TASK-CONFIRM).
 *
 * Deliberately a separate store from [AssistantActionStore]: that one belongs to the
 * assistant device-tools contract (dispatch through AssistantDeviceToolProvider); mixing
 * automation reminders into it would break its single-owner invariant. This store mirrors
 * its *pattern* — Room flow, cap with oldest eviction, seven-day cleanup,
 * `database.withTransaction` on every multi-step write — without sharing its dispatch.
 *
 * Ownership policy (heartbeat contract):
 *   - The store never dispatches effects. `acknowledge`/`dismiss` only consume the row;
 *     "Open conversation" is an intent, not a store operation.
 *   - The store does not know about the `HEARTBEAT_OK` sentinel — the scheduler alone
 *     decides actionability (filter lives in HeartbeatScheduler).
 *   - [consumeDueReminders] is the ONLY writer that clears `remindAtEpochMs` back to null
 *     (fetch-and-clear); without that atomic clear the 60-second tick would re-post the
 *     same vencida row every minute.
 */
class TaskConfirmationStore(
    private val chatDao: ChatDao,
    private val database: ChatDatabase,
) {

    /** Values a caller wants to persist; the store assigns identity and timestamps. */
    data class Draft(
        val source: TaskConfirmationSource,
        val conversationId: String,
        val modelMessageId: String?,
        val title: String,
        val bodyText: String,
    )

    /** Pending confirmations, newest first. Narrow query — collected only by the chat banner. */
    val pendingConfirmations: Flow<List<TaskConfirmationEntity>> =
        chatDao.observePendingTaskConfirmations()

    /**
     * Stages a confirmation. Idempotent on the dedup key
     * (sourceType, conversationId, modelMessageId): re-staging the same generation
     * returns the existing row untouched. Eviction past [MAX_CONFIRMATIONS] happens
     * in the same transaction, so the cap is atomic.
     */
    suspend fun stage(draft: Draft): TaskConfirmationEntity = withContext(Dispatchers.IO) {
        require(draft.title.isNotBlank()) { "Task confirmation title must not be blank" }
        require(draft.bodyText.isNotBlank()) { "Task confirmation body must not be blank" }
        val row = TaskConfirmationEntity(
            id = UUID.randomUUID().toString(),
            sourceType = draft.source.name,
            conversationId = draft.conversationId,
            modelMessageId = draft.modelMessageId,
            title = draft.title.trim(),
            bodyText = draft.bodyText.trim(),
            createdAtEpochMs = System.currentTimeMillis(),
            remindAtEpochMs = null,
            status = TaskConfirmationStatus.PENDING.name,
        )
        database.withTransaction {
            val existing = chatDao.findTaskConfirmationByDedupKey(
                sourceType = row.sourceType,
                conversationId = row.conversationId,
                modelMessageId = row.modelMessageId,
            )
            if (existing != null) return@withTransaction existing
            // INSERT is IGNORE: -1 means the unique index rejected the row (another writer won
            // the dedup key). Returning the unsaved `row` would hand callers an id that does not
            // exist, so surface the winner instead and skip eviction/retention.
            if (chatDao.insertTaskConfirmation(row) == -1L) {
                return@withTransaction chatDao.findTaskConfirmationByDedupKey(
                    sourceType = row.sourceType,
                    conversationId = row.conversationId,
                    modelMessageId = row.modelMessageId,
                ) ?: error("Task confirmation insert ignored but no dedup winner found")
            }
            chatDao.deleteTaskConfirmationsBeyondCap(MAX_CONFIRMATIONS)
            // Retention rides the only growth point. Heartbeat-only cleanup never ran when the
            // heartbeat/daemon was off, so task confirmations (and resolved tombstones) piled up.
            chatDao.cleanupTaskConfirmations(row.createdAtEpochMs - CLEANUP_AGE_MS)
            row
        }
    }

    /** User resolution — single winner: returns false when the row was not PENDING. */
    suspend fun acknowledge(id: String): Boolean =
        consume(id, TaskConfirmationStatus.ACKNOWLEDGED)

    /** User resolution — single winner: returns false when the row was not PENDING. */
    suspend fun dismiss(id: String): Boolean =
        consume(id, TaskConfirmationStatus.DISMISSED)

    /** Arms a snooze window. A row resolved by another surface is not re-armed (false). */
    suspend fun snooze(id: String, minutes: Int): Boolean = withContext(Dispatchers.IO) {
        require(minutes > 0) { "Snooze minutes must be positive" }
        val due = System.currentTimeMillis() + minutes * 60_000L
        chatDao.armTaskConfirmationReminder(id = id, remindAtEpochMs = due) == 1
    }

    /**
     * Fetch-and-clear for the scheduler's snooze due-gate. Returns the rows that were
     * PENDING with an armed reminder ≤ [now] AND clears `remindAtEpochMs` on each in
     * the same transaction. Rows resolved by another surface between the SELECT and the
     * UPDATE are dropped from the batch (the clear UPDATE carries `status='PENDING'`
     * as a predicate and reports 0 rows).
     */
    suspend fun consumeDueReminders(now: Long): List<TaskConfirmationEntity> =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val due = chatDao.selectDueTaskConfirmations(now)
                val posted = mutableListOf<TaskConfirmationEntity>()
                for (row in due) {
                    if (chatDao.clearTaskConfirmationReminderIfStillPending(row.id) == 1) {
                        posted += row
                    }
                }
                posted
            }
        }

    /** PENDING rows with an armed snooze; used to re-arm reminder alarms after a reboot. */
    suspend fun armedReminders(): List<TaskConfirmationEntity> = withContext(Dispatchers.IO) {
        chatDao.selectDueTaskConfirmations(Long.MAX_VALUE)
    }

    /**
     * PENDING rows with NO armed snooze; used to re-arm the deferred re-post alarms after
     * a reboot. Complement of [armedReminders]: the retry-post contract never touches
     * `remindAtEpochMs`, so those rows are invisible to the snooze query above. Safe for
     * rows whose notification was already posted — the retry receiver re-reads the row
     * and its still-pending / not-foreground / canPost guards make a redundant firing a
     * no-op that simply re-arms.
     */
    suspend fun pendingWithoutSnooze(): List<TaskConfirmationEntity> = withContext(Dispatchers.IO) {
        chatDao.selectPendingWithoutSnooze()
    }

    suspend fun get(id: String): TaskConfirmationEntity? = withContext(Dispatchers.IO) {
        chatDao.getTaskConfirmation(id)
    }

    /** Age-based cleanup (7 days). Runs at every staging and on the heartbeat's retention sweep. */
    suspend fun cleanupOld(
        cutoffEpochMs: Long = System.currentTimeMillis() - CLEANUP_AGE_MS,
    ): Int = withContext(Dispatchers.IO) {
        chatDao.cleanupTaskConfirmations(cutoffEpochMs)
    }

    private suspend fun consume(id: String, target: TaskConfirmationStatus): Boolean =
        withContext(Dispatchers.IO) {
            chatDao.transitionTaskConfirmation(
                id = id,
                fromStatus = TaskConfirmationStatus.PENDING.name,
                toStatus = target.name,
            ) == 1
        }

    companion object {
        const val MAX_CONFIRMATIONS = 20
        const val CLEANUP_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private val MARKDOWN_LINK = Regex("""\[([^\]]*)]\(([^)\s]+)\)""")

        /**
         * Plain-text projection of an actionable automation result for the rich-confirmation
         * surfaces (notification shade, chat banner, bottom card). None of them render
         * markdown, so bold/italic markers, heading hashes, backticks, and bullet syntax are
         * stripped ONCE at staging — the durable row stores the clean text and every surface
         * inherits the same body. Shared by every staging call site (heartbeat, tasks,
         * future loops) so the projection has exactly one owner.
         *
         * Emphasis runs strip BEFORE heading markers: "**###**" must end up empty, not leave
         * a bare "###" behind. Markdown links keep their label and URL ("label (url)") because
         * the notification shade cannot render them and dropping the URL would lose information;
         * blockquote markers are dropped. Content is otherwise untouched: no line-merging, no
         * truncation (surfaces clamp with maxLines/BigText themselves).
         */
        fun plainTextForConfirmation(raw: String): String = buildString {
            for (line in raw.lines()) {
                var text = line.trimStart()
                text = MARKDOWN_LINK.replace(text) { match ->
                    val label = match.groupValues[1].trim()
                    val url = match.groupValues[2].trim()
                    if (label.isEmpty() || label == url) url else "$label ($url)"
                }
                text = text.replace("**", "").replace("__", "")
                    .replace("`", "")
                    .trimStart()
                while (text.startsWith("#") || text.startsWith(">")) text = text.substring(1).trimStart()
                text = text.removePrefix("- ").removePrefix("* ").removePrefix("+ ")
                    .removePrefix("• ")
                    .trimStart()
                if (text.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(text)
                }
            }
            toString()
        }
    }
}
