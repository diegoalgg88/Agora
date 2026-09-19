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
        // ALWAYS call startForeground() first, unconditionally, before checking the action —
        // this is what makes stop() safe against the classic start→stop race
        // (ForegroundServiceDidNotStartInTimeException / SERVICE_FOREGROUND_CRASH_MSG, owner
        // report 2026-09-18): stop() below no longer calls Context.stopService() directly (which
        // can reach AMS before this very onStartCommand runs and destroy the ServiceRecord while
        // `fgRequired` is still pending); it re-enters this same onStartCommand via ACTION_STOP,
        // and Android serializes all onStartCommand calls for one component on this thread in
        // the order they were dispatched. So a start followed immediately by a stop always sees
        // this line run for the start first.
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
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
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
        private const val ACTION_STOP = "com.newoether.agora.assistant.live.action.STOP"

        /** True between start() and the first stop() request. VoiceModeActivity calls stop()
         *  from up to three paths on teardown (ENDED state change, hangUp, onDestroy), so
         *  without this guard each redundant stop() would re-create the service (with a
         *  notification flash) just to immediately stop it — and when start() never ran
         *  (RECORD_AUDIO denied), onDestroy's stop() would create a foreground service that
         *  was never wanted at all. */
        @Volatile
        private var started = false

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
            started = true
            context.startForegroundService(Intent(context, LiveVoiceForegroundService::class.java))
        }

        fun stop(context: Context) {
            if (!started) return
            started = false
            // Routed through onStartCommand (ACTION_STOP), never Context.stopService() directly
            // — see the comment on onStartCommand for why. Plain startService (not
            // startForegroundService) so delivering the command never re-arms `fgRequired`.
            try {
                context.startService(
                    Intent(context, LiveVoiceForegroundService::class.java).setAction(ACTION_STOP),
                )
            } catch (e: IllegalStateException) {
                // App left the background-eligible window mid-call (the FGS died on its own
                // while the activity was already backgrounded). Direct external stop is safe
                // here: the fgRequired race window only exists immediately after start(), and
                // reaching this catch means the service is either long-promoted to foreground
                // or already gone — stopService on a stopped service is a no-op.
                context.stopService(
                    Intent(context, LiveVoiceForegroundService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}
