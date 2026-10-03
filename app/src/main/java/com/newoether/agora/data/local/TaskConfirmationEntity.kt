package com.newoether.agora.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable state of a rich task confirmation (plan PLAN-20261002-TASK-CONFIRM).
 *
 * The table is CREATE-only from v37 (MIGRATION_37_38); no existing table is touched.
 * Room is the single durable truth. The closed-enum invariant of [sourceType] and
 * [status] is enforced by [TaskConfirmationSource]/[TaskConfirmationStatus] and the
 * store — deliberately NOT by SQL CHECK constraints (Room ≥2.7 compares CHECKs during
 * post-migration schema validation, and declaring them only in the migration SQL would
 * fail that validation).
 *
 * Invariants (development/ heartbeat contract — rich task confirmations section):
 *   - dedup key = (sourceType, conversationId, modelMessageId), enforced by a unique index;
 *   - [remindAtEpochMs] is non-null only inside an active snooze window, and the ONLY
 *     writer that clears it back to null is the fetch-and-clear due-gate
 *     (`TaskConfirmationStore.consumeDueReminders`);
 *   - rows are ephemeral: cap 20 PENDING rows with oldest eviction (resolved rows are dedup
 *     tombstones bounded only by the 7-day cleanup), and deliberately
 *     excluded from the portable `.agora` archive.
 */
@Entity(
    tableName = "task_confirmations",
    indices = [
        Index(value = ["sourceType", "conversationId", "modelMessageId"], unique = true),
        Index(value = ["status", "remindAtEpochMs"]),
        Index(value = ["createdAtEpochMs"]),
    ],
)
data class TaskConfirmationEntity(
    @PrimaryKey val id: String,
    val sourceType: String,
    val conversationId: String,
    /** Unique per generation (`TaskExecutionEngine.runOnceLocked` mints a fresh UUID per run). */
    val modelMessageId: String?,
    val title: String,
    val bodyText: String,
    val createdAtEpochMs: Long,
    /** Snooze deadline; null unless the user explicitly postponed this confirmation. */
    val remindAtEpochMs: Long? = null,
    val status: String = TaskConfirmationStatus.PENDING.name,
)

/** Closed enum for [TaskConfirmationEntity.sourceType]. Wire value = enum name. */
enum class TaskConfirmationSource {
    HEARTBEAT,
    TASK,
    LOOP,
    ;

    companion object {
        fun fromWire(value: String): TaskConfirmationSource? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/** Closed enum for [TaskConfirmationEntity.status]. Wire value = enum name. */
enum class TaskConfirmationStatus {
    PENDING,
    ACKNOWLEDGED,
    DISMISSED,
    ;

    companion object {
        fun fromWire(value: String): TaskConfirmationStatus? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * Presentation mode for actionable automation results (Settings → Automation). Universal
 * Installer analogue: `ExternalOpenMode.Notification` vs `AutoNotification`.
 *   - PROMPT: current behaviour — durable PENDING row + banner + actionable notification.
 *   - AUTO: informational only — auto-dismiss notification, NO staging, NO banner: a
 *     reminder nobody is asked to confirm must not occupy durable state.
 */
enum class TaskConfirmationMode {
    PROMPT,
    AUTO,
    ;

    companion object {
        val DEFAULT = PROMPT

        fun fromWire(value: String): TaskConfirmationMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * Card anchor for the rich confirmation activity. Universal Installer analogue:
 * `InstallUiStyle.Dialog` (centered) vs `InstallUiStyle.Sheet` (bottom).
 */
enum class TaskConfirmationCardStyle {
    BOTTOM,
    CENTERED,
    ;

    companion object {
        val DEFAULT = BOTTOM

        fun fromWire(value: String): TaskConfirmationCardStyle =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DEFAULT
    }
}
