package com.newoether.agora.service

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.newoether.agora.MainActivity
import com.newoether.agora.R
import com.newoether.agora.automation.TaskConfirmationReceiver
import com.newoether.agora.data.TaskConfirmationStore
import com.newoether.agora.data.local.TaskConfirmationSource
import com.newoether.agora.ui.automation.TaskConfirmationActivity
import com.newoether.agora.util.DebugLog

/**
 * Posts rich task-confirmation notifications with actions (plan PLAN-20261002-TASK-CONFIRM).
 *
 * Deliberately independent from [HeartbeatNotifier]:
 *   - separate versioned channel (`task_confirmation_v1`) so importance can be HIGH
 *     with actions without touching the plain failure-notification channel;
 *   - notification IDs live on a dedicated base (51000+) — they cannot collide with the
 *     pre-existing 1001 collision between HeartbeatNotifier and AutoBackupManager;
 *   - the ID is derived from the confirmation ID, so re-posting the same confirmation
 *     (snooze re-fire, dedup) replaces the previous notification instead of stacking.
 *
 * Fail-open by contract: when the platform refuses the post (permission revoked, channel
 * disabled), the durable row survives and stays visible in the chat banner — no result
 * is ever orphaned.
 */
class TaskPromptNotifier(
    private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    /** Whether a prompt can be posted at all; callers gate the whole notification path on this. */
    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return manager.areNotificationsEnabled()
    }

    /**
     * Prompts (actionable) live on a HIGH channel; informational AUTO posts get their own
     * DEFAULT channel so a result nobody is asked to answer does not heads-up like a prompt
     * and the user can mute it independently.
     */
    private fun ensureChannel(info: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        val id = if (info) INFO_CHANNEL_ID else CHANNEL_ID
        if (system.getNotificationChannel(id) != null) return
        val channel = if (info) {
            NotificationChannel(
                id,
                context.getString(R.string.task_confirmation_info_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.task_confirmation_info_channel_desc)
                setShowBadge(true)
            }
        } else {
            NotificationChannel(
                id,
                context.getString(R.string.task_confirmation_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.task_confirmation_channel_desc)
                enableVibration(true)
                setShowBadge(true)
            }
        }
        system.createNotificationChannel(channel)
    }

    /**
     * Posts the rich prompt. The display title is resolved HERE from the row's sourceType —
     * surfaces must never render the durable `row.title` (a neutral "Heartbeat" label),
     * they localize their own headers. This was the 2026-10-03 device-run finding: the
     * notification arrived titled "Heartbeat" in English on a Spanish device.
     *
     * @return false when the post was refused (POST_NOTIFICATIONS denied / channel muted).
     *   Callers must record it — a silently dropped prompt leaves the row visible only in
     *   the in-chat banner, invisible to a user who never opens the app (Universal
     *   Installer's P2 rule: never strand a result with nothing on screen).
     */
    fun post(
        confirmationId: String,
        sourceType: String,
        rowTitle: String,
        body: String,
        conversationId: String,
    ): Boolean {
        if (!canPost()) {
            DebugLog.w(TAG, "Task confirmation post refused (notifications blocked): id=$confirmationId src=$sourceType")
            return false
        }
        ensureChannel()
        val notificationId = notificationIdFor(confirmationId)
        val title = displayTitleFor(sourceType, rowTitle)

        // "View" — opens the rich bottom-anchored card with Confirm/Dismiss/Snooze.
        val contentIntent = PendingIntent.getActivity(
            context,
            requestCode(confirmationId, REQUEST_CONTENT),
            Intent(context, TaskConfirmationActivity::class.java).apply {
                putExtra(TaskConfirmationActivity.EXTRA_CONFIRMATION_ID, confirmationId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val confirmIntent = PendingIntent.getBroadcast(
            context,
            requestCode(confirmationId, REQUEST_CONFIRM),
            Intent(context, TaskConfirmationReceiver::class.java).apply {
                action = TaskConfirmationReceiver.ACTION_CONFIRM
                putExtra(TaskConfirmationReceiver.EXTRA_CONFIRMATION_ID, confirmationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val snoozeIntent = PendingIntent.getBroadcast(
            context,
            requestCode(confirmationId, REQUEST_SNOOZE),
            Intent(context, TaskConfirmationReceiver::class.java).apply {
                action = TaskConfirmationReceiver.ACTION_SNOOZE
                putExtra(TaskConfirmationReceiver.EXTRA_CONFIRMATION_ID, confirmationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Swipe-away == Dismiss: a prompt nobody answered must not linger as a dead row.
        val deleteIntent = PendingIntent.getBroadcast(
            context,
            requestCode(confirmationId, REQUEST_DISMISS),
            Intent(context, TaskConfirmationReceiver::class.java).apply {
                action = TaskConfirmationReceiver.ACTION_DISMISS
                putExtra(TaskConfirmationReceiver.EXTRA_CONFIRMATION_ID, confirmationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // "Open conversation" navigates via the verified extra pattern (MainActivity's
        // notificationConversationId flow) — NOT HeartbeatNotifier's OPEN_HEARTBEAT
        // action, which no one handles.
        val openConversationIntent = PendingIntent.getActivity(
            context,
            requestCode(confirmationId, REQUEST_OPEN_CONVERSATION),
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // A question must arrive as a heads-up, not wait quietly in the shade (UI's
            // InstallPromptNotifier finding: DEFAULT only added a shade row).
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setDeleteIntent(deleteIntent)
            .addAction(0, context.getString(R.string.task_confirmation_action_confirm), confirmIntent)
            .addAction(0, context.getString(R.string.task_confirmation_action_snooze), snoozeIntent)
            .addAction(
                0,
                context.getString(R.string.task_confirmation_action_open_conversation),
                openConversationIntent,
            )
            .build()

        return runCatching { manager.notify(notificationId, notification) }
            .onFailure { DebugLog.w(TAG, "Failed to post task confirmation", it) }
            .isSuccess
    }

    /** Removes the notification for [confirmationId] from any consuming surface. */
    fun cancel(confirmationId: String) {
        runCatching { manager.cancel(notificationIdFor(confirmationId)) }
            .onFailure { DebugLog.w(TAG, "Failed to cancel task confirmation", it) }
    }

    /**
     * Source-aware display title — the single decision point every surface shares.
     * HEARTBEAT localizes its header (its durable row title is the neutral "Heartbeat"
     * label); TASK/LOOP surface the durable row title directly — F8 stages the task's own
     * name there, which identifies the origin better than any generic string.
     */
    internal fun displayTitleFor(sourceType: String, rowTitle: String): String = when (
        TaskConfirmationSource.fromWire(sourceType)
    ) {
        TaskConfirmationSource.HEARTBEAT ->
            context.getString(titleResFor(sourceType))
        TaskConfirmationSource.TASK,
        TaskConfirmationSource.LOOP,
        null ->
            rowTitle.ifBlank { context.getString(titleResFor(sourceType)) }
    }

    /**
     * AUTO-mode informational post (Universal Installer's AutoNotification analogue): no
     * durable row exists, so this notification carries NO actions, NO delete intent, and
     * auto-cancels on tap. ID derives from the content (source+message) so consecutive
     * actionable results replace each other instead of piling up in the shade.
     *
     * @return false when the post was refused; AUTO has no durable row, so the caller has
     *   nothing to surface — the structured log is the only trace of the lost result.
     */
    fun postInfo(
        sourceType: String,
        title: String,
        body: String,
        conversationId: String,
        modelMessageId: String?,
    ): Boolean {
        if (!canPost()) {
            DebugLog.w(TAG, "Task info post refused (notifications blocked): src=$sourceType conv=$conversationId")
            return false
        }
        ensureChannel(info = true)
        val stableKey = "$sourceType:${conversationId}:${modelMessageId ?: body.hashCode()}"
        val notificationId = notificationIdFor(stableKey)
        val openConversationIntent = PendingIntent.getActivity(
            context,
            requestCode(stableKey, REQUEST_OPEN_CONVERSATION),
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, INFO_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(displayTitleFor(sourceType, title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setContentIntent(openConversationIntent)
            .build()
        return runCatching { manager.notify(notificationId, notification) }
            .onFailure { DebugLog.w(TAG, "Failed to post task info notification", it) }
            .isSuccess
    }

    /**
     * Arms a daemon-independent one-shot alarm that re-posts a snoozed confirmation. The
     * heartbeat tick used to be the only re-post path, so with the daemon off a snooze never
     * came back. Inexact on purpose: a reminder does not warrant the exact-alarm permission.
     * The alarm is never cancelled on resolution; a stale firing finds no due row and no-ops.
     */
    fun scheduleReminder(confirmationId: String, triggerAtMs: Long) {
        val trigger = triggerAtMs.coerceAtLeast(System.currentTimeMillis() + MIN_REMINDER_DELAY_MS)
        runCatching {
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarms.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                trigger,
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, TaskConfirmationReceiver::class.java).apply {
                        action = TaskConfirmationReceiver.ACTION_REMIND
                        // Distinct data URI => distinct PendingIntent per confirmation.
                        data = "agora://task-confirmation/remind/$confirmationId".toUri()
                        putExtra(TaskConfirmationReceiver.EXTRA_CONFIRMATION_ID, confirmationId)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }.onFailure { DebugLog.w(TAG, "Failed to arm snooze reminder", it) }
    }

    /**
     * Deferred re-post when [post] was refused because notifications were blocked at staging
     * time (P2, adapted headless: a run cannot launch a dialog, so the retry waits for the
     * user to unblock). Deliberately does NOT touch `remindAtEpochMs` — that field is the
     * snooze contract (the banner hides the row until the deadline) and a blocked
     * notification must not hide the banner row too, which is the only visible surface
     * while blocked. The receiver re-reads the row and re-posts only while it is still
     * PENDING, so a stale firing after resolution is a no-op.
     */
    fun schedulePostRetry(confirmationId: String) {
        val trigger = System.currentTimeMillis() + POST_RETRY_DELAY_MS
        runCatching {
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarms.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                trigger,
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, TaskConfirmationReceiver::class.java).apply {
                        action = TaskConfirmationReceiver.ACTION_RETRY_POST
                        data = "agora://task-confirmation/retry/$confirmationId".toUri()
                        putExtra(TaskConfirmationReceiver.EXTRA_CONFIRMATION_ID, confirmationId)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }.onFailure { DebugLog.w(TAG, "Failed to arm post retry", it) }
    }

    /**
     * Fetch-and-clear of due snoozes plus the heads-up re-post. Shared by the heartbeat tick and
     * the reminder alarm so both obey one rule: the row is always released (the banner shows it
     * again), and the notification is only re-posted when backgrounded and allowed.
     */
    suspend fun postDueReminders(store: TaskConfirmationStore, appInForeground: Boolean) {
        val due = store.consumeDueReminders(System.currentTimeMillis())
        if (due.isEmpty() || appInForeground || !canPost()) return
        for (row in due) {
            post(
                confirmationId = row.id,
                sourceType = row.sourceType,
                rowTitle = row.title,
                body = row.bodyText,
                conversationId = row.conversationId,
            )
        }
    }

    /**
     * Stable per (confirmationId, purpose) request codes so PendingIntents for different
     * confirmations never collide and re-posts update in place.
     */
    private fun requestCode(confirmationId: String, purpose: Int): Int =
        (confirmationId.hashCode() xor purpose) and 0x7FFFFFFF

    companion object {
        private const val TAG = "TaskPromptNotifier"
        const val CHANNEL_ID = "task_confirmation_v1"
        const val INFO_CHANNEL_ID = "task_confirmation_info_v1"
        private const val MIN_REMINDER_DELAY_MS = 1_000L

        /**
         * Deferred re-post cadence for a notification refused at staging time. Long enough
         * not to spam alarms while the user keeps notifications blocked; short enough that
         * unblocking surfaces the pending result on the next cycle.
         */
        private const val POST_RETRY_DELAY_MS = 15L * 60_000L

        /** Stable notification ID for a confirmation ID (or an AUTO-mode content key). */
        internal fun notificationIdFor(key: String): Int =
            NOTIFICATION_ID_BASE + (key.hashCode() and HASH_MASK)

        /**
         * Resource fallback per source: HEARTBEAT has a dedicated result header; TASK/LOOP
         * fall back to the settings label only when their durable row title is blank.
         */
        @StringRes
        internal fun titleResFor(sourceType: String): Int = when (
            TaskConfirmationSource.fromWire(sourceType)
        ) {
            TaskConfirmationSource.HEARTBEAT -> R.string.task_confirmation_result_title
            TaskConfirmationSource.TASK,
            TaskConfirmationSource.LOOP,
            null -> R.string.task_confirmation_settings_title
        }
        internal const val NOTIFICATION_ID_BASE = 51000
        // 20 bits: with up to 20 live confirmations a 14-bit space collided ~1% of the time,
        // and a collision makes one notification replace (or cancel) another pending one.
        private const val HASH_MASK = 0xFFFFF
        private const val REQUEST_CONTENT = 0x51
        private const val REQUEST_CONFIRM = 0x52
        private const val REQUEST_DISMISS = 0x53
        private const val REQUEST_SNOOZE = 0x55
        private const val REQUEST_OPEN_CONVERSATION = 0x54
    }
}
