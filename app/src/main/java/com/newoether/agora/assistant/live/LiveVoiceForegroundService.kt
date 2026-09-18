package com.newoether.agora.assistant.live

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.newoether.agora.MainActivity
import com.newoether.agora.R

/**
 * Keeps the process alive while a Live voice call runs so microphone capture survives the UI
 * going to background (Android 14+ requires `foregroundServiceType="microphone"`).
 *
 * The service owns no call state — [LiveVoiceSessionController] does — and is only ever started
 * from the foreground `VoiceModeScreen` after `RECORD_AUDIO` is granted (a microphone-type FGS
 * cannot start while the app is in the background, and never from automation). `START_NOT_STICKY`
 * guarantees process death never revives a hidden microphone capture.
 */
internal class LiveVoiceForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.live_voice_notification_title))
            .setContentText(getString(R.string.live_voice_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "live_voice_call"
        private const val NOTIFICATION_ID = 424

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.live_voice_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = context.getString(R.string.live_voice_channel_desc)
                        setShowBadge(false)
                        setSound(null, null)
                    },
                )
            }
            context.startForegroundService(Intent(context, LiveVoiceForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveVoiceForegroundService::class.java))
        }
    }
}
