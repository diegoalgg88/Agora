package com.newoether.agora.service

import android.Manifest
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
import com.newoether.agora.MainActivity
import com.newoether.agora.R
import com.newoether.agora.automation.TaskConfirmationReceiver
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

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        if (system.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.task_confirmation_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.task_confirmation_channel_desc)
            enableVibration(true)
            setShowBadge(true)
        }
        system.createNotificationChannel(channel)
    }

    /**
     * Posts the rich prompt. The display title is resolved HERE from the row's sourceType —
     * surfaces must never render the durable `row.title` (a neutral "Heartbeat" label),
     * they localize their own headers. This was the 2026-10-03 device-run finding: the
     * notification arrived titled "Heartbeat" in English on a Spanish device.
     */
    fun post(
        confirmationId: String,
        sourceType: String,
        body: String,
        conversationId: String,
    ) {
        if (!canPost()) return
        ensureChannel()
        val notificationId = notificationIdFor(confirmationId)
        val title = displayTitleFor(sourceType)

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
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setDeleteIntent(deleteIntent)
            .addAction(0, context.getString(R.string.task_confirmation_action_confirm), confirmIntent)
            .addAction(
                0,
                context.getString(R.string.task_confirmation_action_open_conversation),
                openConversationIntent,
            )
            .build()

        runCatching { manager.notify(notificationId, notification) }
            .onFailure { DebugLog.w(TAG, "Failed to post task confirmation", it) }
    }

    /** Removes the notification for [confirmationId] from any consuming surface. */
    fun cancel(confirmationId: String) {
        runCatching { manager.cancel(notificationIdFor(confirmationId)) }
            .onFailure { DebugLog.w(TAG, "Failed to cancel task confirmation", it) }
    }

    /** Localized display title by source — the durable row.title stays neutral by contract. */
    internal fun displayTitleFor(sourceType: String): String =
        context.getString(titleResFor(sourceType))

    /**
     * AUTO-mode informational post (Universal Installer's AutoNotification analogue): no
     * durable row exists, so this notification carries NO actions, NO delete intent, and
     * auto-cancels on tap. ID derives from the content (source+message) so consecutive
     * actionable results replace each other instead of piling up in the shade.
     */
    fun postInfo(
        sourceType: String,
        body: String,
        conversationId: String,
        modelMessageId: String?,
    ) {
        if (!canPost()) return
        ensureChannel()
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
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(displayTitleFor(sourceType))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setContentIntent(openConversationIntent)
            .build()
        runCatching { manager.notify(notificationId, notification) }
            .onFailure { DebugLog.w(TAG, "Failed to post task info notification", it) }
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

        /** Stable notification ID for a confirmation ID (or an AUTO-mode content key). */
        internal fun notificationIdFor(key: String): Int =
            NOTIFICATION_ID_BASE + (key.hashCode() and HASH_MASK)

        /**
         * Single source of the localized header for every surface (notification, banner, card),
         * so they can never disagree. TASK/LOOP have no staging call sites yet; the settings
         * label is the safest neutral localized fallback until a future feature adds them.
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
        private const val REQUEST_OPEN_CONVERSATION = 0x54
    }
}
