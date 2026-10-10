package com.newoether.agora.service

import android.content.BroadcastReceiver
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import com.newoether.agora.AgoraApplication
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-arms task alarms after a reboot or app update. AlarmManager alarms do not survive either,
 * so on BOOT_COMPLETED / MY_PACKAGE_REPLACED we touch the scheduler — accessing the container
 * starts it, and it re-schedules from each task's persisted nextRunAt.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                DebugLog.d("BootReceiver", "re-arming automation alarms after ${intent.action}")
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val container = (context.applicationContext as AgoraApplication)
                            .awaitContainer()
                        if (container == null) {
                            DebugLog.w(
                                "BootReceiver",
                                "Skipping alarm re-arm while database startup is blocked",
                            )
                            return@launch
                        }
                        val scheduler = container.automationScheduler
                        scheduler.start()
                        scheduler.refreshAndAwait(
                            recalculateForClockChange = intent.action == Intent.ACTION_TIME_CHANGED ||
                                intent.action == Intent.ACTION_TIMEZONE_CHANGED,
                        )
                        // Snooze reminder alarms do not survive reboot/update either.
                        runCatching {
                            container.taskConfirmationStore.armedReminders().forEach { row ->
                                row.remindAtEpochMs?.let {
                                    container.taskPromptNotifier.scheduleReminder(row.id, it)
                                }
                            }
                        }.onFailure { DebugLog.w("BootReceiver", "Failed to re-arm snooze reminders", it) }
                        // Deferred re-post alarms (P2) die with the reboot too, but they never
                        // touch remindAtEpochMs — armedReminders() cannot see them. Re-arm from
                        // the complementary query. Source-agnostic by design: the retry arming
                        // point is shared across TASK/LOOP/HEARTBEAT, and the receiver's
                        // still-pending / not-foreground / canPost guards make a redundant
                        // firing a no-op for rows whose notification was already posted.
                        runCatching {
                            container.taskConfirmationStore.pendingWithoutSnooze().forEach { row ->
                                container.taskPromptNotifier.schedulePostRetry(row.id)
                            }
                        }.onFailure { DebugLog.w("BootReceiver", "Failed to re-arm retry-post alarms", it) }
                    } catch (e: Exception) {
                        DebugLog.e("BootReceiver", "Failed to re-arm automation alarms", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
