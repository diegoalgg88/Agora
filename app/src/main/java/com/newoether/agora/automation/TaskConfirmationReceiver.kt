package com.newoether.agora.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.newoether.agora.AgoraApplication
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Minimal receiver for task-confirmation notification actions. It only consumes the
 * durable row by exact ID and cancels the notification; it never decides anything.
 *
 * Uses `goAsync()` + a coroutine because the store call is a Room transaction and the
 * receiver must not block the main thread. (Distinct from AutomationAlarmReceiver, which
 * only enqueues WorkManager synchronously — that pattern does not fit Room writes.)
 */
class TaskConfirmationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val confirmationId = intent.getStringExtra(EXTRA_CONFIRMATION_ID) ?: return
        val action = intent.action ?: return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = appContext as? AgoraApplication ?: return@launch
                val container = app.awaitContainer() ?: return@launch
                when (action) {
                    ACTION_CONFIRM -> container.taskConfirmationStore.acknowledge(confirmationId)
                    ACTION_DISMISS -> container.taskConfirmationStore.dismiss(confirmationId)
                    ACTION_SNOOZE -> {
                        // Cancel first, arm after: the snooze notification disappears now and
                        // the alarm (daemon-independent) brings it back at the deadline.
                        val armed = container.taskConfirmationStore
                            .snooze(confirmationId, DEFAULT_SNOOZE_MINUTES)
                        container.taskPromptNotifier.cancel(confirmationId)
                        if (armed) {
                            container.taskPromptNotifier.scheduleReminder(
                                confirmationId,
                                System.currentTimeMillis() + DEFAULT_SNOOZE_MINUTES * 60_000L,
                            )
                        }
                        return@launch
                    }
                    ACTION_REMIND -> {
                        container.taskPromptNotifier.postDueReminders(
                            container.taskConfirmationStore,
                            AppForegroundTracker.isInForeground,
                        )
                        return@launch
                    }
                    ACTION_RETRY_POST -> {
                        // Deferred re-post for a notification refused at staging (blocked).
                        // Re-read the row: a stale firing after resolution is a no-op, and a
                        // row still blocked re-arms the cycle until it can surface.
                        val row = container.taskConfirmationStore.get(confirmationId) ?: return@launch
                        val stillPending =
                            row.status == com.newoether.agora.data.local.TaskConfirmationStatus
                                .PENDING.name
                        if (!stillPending) return@launch
                        if (AppForegroundTracker.isInForeground) return@launch
                        if (container.taskPromptNotifier.canPost()) {
                            val posted = container.taskPromptNotifier.post(
                                confirmationId = row.id,
                                sourceType = row.sourceType,
                                rowTitle = row.title,
                                body = row.bodyText,
                                conversationId = row.conversationId,
                            )
                            if (!posted) container.taskPromptNotifier.schedulePostRetry(row.id)
                        } else {
                            container.taskPromptNotifier.schedulePostRetry(row.id)
                        }
                        return@launch
                    }
                    else -> {
                        DebugLog.w(TAG, "Unknown action $action ignored")
                        return@launch
                    }
                }
                // Cancel unconditionally: if another surface already consumed the row (the
                // store reports false), the notification must still not linger as a dead button.
                container.taskPromptNotifier.cancel(confirmationId)
            } catch (t: Throwable) {
                DebugLog.w(TAG, "Failed to consume task confirmation", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "TaskConfirmationReceiver"
        const val ACTION_CONFIRM = "com.newoether.agora.automation.TASK_CONFIRMATION_CONFIRM"
        const val ACTION_DISMISS = "com.newoether.agora.automation.TASK_CONFIRMATION_DISMISS"
        const val ACTION_SNOOZE = "com.newoether.agora.automation.TASK_CONFIRMATION_SNOOZE"
        const val ACTION_REMIND = "com.newoether.agora.automation.TASK_CONFIRMATION_REMIND"
        const val ACTION_RETRY_POST = "com.newoether.agora.automation.TASK_CONFIRMATION_RETRY_POST"

        /** Notification Snooze has no picker; it uses the shortest card option. */
        const val DEFAULT_SNOOZE_MINUTES = 10
        const val EXTRA_CONFIRMATION_ID = "confirmation_id"
    }
}
