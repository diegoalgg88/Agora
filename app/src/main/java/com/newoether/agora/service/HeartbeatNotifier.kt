package com.newoether.agora.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.newoether.agora.MainActivity
import com.newoether.agora.R

/**
 * Sends heartbeat push notifications when the app is backgrounded.
 *
 * The failure alert deep-links into the heartbeat conversation via the verified
 * extra pattern (`MainActivity.EXTRA_CONVERSATION_ID` + `agora://conversation/{id}`) —
 * the same shape `AgoraForegroundService.createPendingIntent` uses. The old
 * `com.newoether.agora.OPEN_HEARTBEAT` action had no handler and is gone.
 *
 * The notification ID must differ from every other poster: Android keys `notify`
 * by (package, tag, id) — the channel does NOT isolate IDs, so the historical
 * `1001` collided with `AutoBackupManager` and a backup run silently replaced
 * the heartbeat failure alert.
 */
class HeartbeatNotifier(
    private val context: Context,
    private val settingsRepository: com.newoether.agora.data.repository.SettingsRepository,
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val CHANNEL_ID = "heartbeat_notifications"

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Heartbeat Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications from background heartbeat checks"
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Sends a heartbeat notification. [conversationId] routes the tap to the
     * heartbeat conversation so the user can read the run log.
     */
    fun sendHeartbeatNotification(title: String, body: String, conversationId: String?) {
        val notificationId = NOTIFICATION_ID
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            conversationId?.let {
                data = Uri.Builder()
                    .scheme("agora")
                    .authority("conversation")
                    .appendPath(it)
                    .build()
                putExtra(MainActivity.EXTRA_CONVERSATION_ID, it)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body.take(200))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .build()

        notificationManager.notify(notificationId, notification)
    }

    companion object {
        /**
         * Stable, collision-free id (the alert replaces a previous unread one — there is
         * only ever one heartbeat conversation). Deliberately NOT 1001: that value is
         * AutoBackupManager's, and Android's notify key ignores the channel.
         */
        const val NOTIFICATION_ID = 1002
    }
}