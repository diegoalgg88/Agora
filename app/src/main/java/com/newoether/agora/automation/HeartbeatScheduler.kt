package com.newoether.agora.automation

import android.content.Context
import com.newoether.agora.data.EmailPoller
import com.newoether.agora.data.EmailStore
import com.newoether.agora.data.HeartbeatManager
import com.newoether.agora.data.NotificationStore
import com.newoether.agora.data.SmsPoller
import com.newoether.agora.data.SmsStore
import com.newoether.agora.data.TaskConfirmationStore
import com.newoether.agora.data.local.TaskConfirmationMode
import com.newoether.agora.data.local.TaskConfirmationSource
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.getOrCreateHeartbeatConversationId
import com.newoether.agora.data.repository.getRecentFinalModelResponses
import com.newoether.agora.data.repository.migrateHeartbeatConversationSetting
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.service.HeartbeatNotifier
import com.newoether.agora.service.TaskPromptNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Markup/whitespace a model may wrap around the HEARTBEAT_OK marker; never content. */
private val DECORATION_CHARS = charArrayOf('*', '_', '`', '"', '\'', ' ', '\n', '\r', '\t')

/**
 * Dedicated scheduler for heartbeat and SMS polling.
 *
 * Runs in the Daemon's coroutine scope. NOT to be confused with [AutomationScheduler]
 * which uses AlarmManager for Task/Loop scheduling.
 *
 * Loop runs every 60 seconds:
 * 1. Checks if heartbeat is due → runs heartbeat
 * 2. Polls SMS if enabled
 */
class HeartbeatScheduler(
    private val appContext: Context,
    private val heartbeatManager: HeartbeatManager,
    private val settingsRepository: SettingsRepository,
    private val smsStore: SmsStore,
    private val smsPoller: SmsPoller,
    private val notificationStore: NotificationStore,
    private val heartbeatNotifier: HeartbeatNotifier,
    private val taskExecutionEngine: TaskExecutionEngine,
    private val appForegroundTracker: AppForegroundTracker,
    private val loopManager: LoopManager,
    private val emailStore: EmailStore,
    private val emailPoller: EmailPoller,
    private val taskConfirmationStore: TaskConfirmationStore,
    private val taskPromptNotifier: TaskPromptNotifier,
) {
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /**
     * Prevents a manual run from overlapping the scheduled loop's run. Compare-and-set
     * (not a volatile check-then-act) so a manual "Run now" racing the 60s tick cannot
     * both pass the guard.
     */
    private val heartbeatInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var migrated = false

    fun start() {
        if (job != null && job!!.isActive) return
        job = scope.launch {
            while (true) {
                delay(60_000) // 60 second loop
                try {
                    if (!migrated) {
                        awaitInitialLoad()
                        getConversationRepository().migrateHeartbeatConversationSetting(
                            settingsRepository,
                        )
                        migrated = true
                    }
                    runCycle()
                } catch (e: Exception) {
                    com.newoether.agora.util.DebugLog.e("HeartbeatScheduler", "Cycle error", e)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Runs a single heartbeat immediately, bypassing the interval/active-hours gate.
     * Safe to call while the scheduler loop is running; overlapping runs are skipped.
     */
    suspend fun runHeartbeatNow() {
        executeHeartbeat()
    }

    /**
     * Fire-and-forget [runHeartbeatNow] launched in the scheduler's own process scope.
     *
     * Settings' "Run now" must NEVER mount the run on a composition scope: navigating from
     * Settings to the chat to watch the generation disposes the Settings composition, which
     * cancelled the in-flight run and finalized it as STOPPED/USER_STOPPED with no log row
     * (verified twice on device, 2026-10-03). The scheduler scope survives navigation and
     * shares the in-flight CAS with the loop, so overlap semantics are unchanged.
     */
    fun runHeartbeatNowAsync(): Job = scope.launch {
        // The scheduler scope has no CoroutineExceptionHandler: an escaping exception would
        // reach the process-level handler and crash the app. The loop path already catches;
        // this fire-and-forget path must hold the same line.
        try {
            executeHeartbeat()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            com.newoether.agora.util.DebugLog.e("HeartbeatScheduler", "Manual heartbeat failed", e)
        }
    }

    private suspend fun runCycle() {
        // Run heartbeat if due
        if (heartbeatManager.isHeartbeatDue()) {
            awaitInitialLoad()
            executeHeartbeat()
        }

        // Rich task confirmations: snooze due-gate (plan PLAN-20261002-TASK-CONFIRM).
        // Runs on every tick — even when the heartbeat itself is not due — so a snoozed
        // reminder re-posts at its own deadline. consumeDueReminders is fetch-and-clear:
        // the same transaction that returns the due rows clears remindAtEpochMs, otherwise
        // this tick would re-post the same row every 60s until resolved. Rows resolved by
        // another surface (notification action, banner, card) between SELECT and UPDATE
        // are dropped by the status='PENDING' predicate.
        // The reminder alarm armed at snooze time (TaskPromptNotifier.scheduleReminder) is the
        // primary re-post path and works with the daemon off; this tick is the backstop for a
        // lost alarm. Both share postDueReminders, and the atomic fetch-and-clear guarantees a
        // row is re-posted by exactly one of them.
        runCatching {
            taskPromptNotifier.postDueReminders(
                taskConfirmationStore,
                appForegroundTracker.isInForeground,
            )
        }.onFailure { error ->
            com.newoether.agora.util.DebugLog.e("HeartbeatScheduler", "Snooze due-gate failed", error)
        }

        // Poll SMS if enabled, rate-limited by the configured poll interval
        if (settingsRepository.smsReadEnabled.value) {
            awaitInitialLoad()
            pollSmsIfDue()
        }

        // Poll email if any account is connected, rate-limited per account
        if (settingsRepository.emailAccounts.value.isNotEmpty()) {
            awaitInitialLoad()
            pollEmailsIfDue()
        }

        // Notifications are push-driven - no polling needed
        // The pending queue is consumed during heartbeat
    }

    private suspend fun awaitInitialLoad() {
        settingsRepository.awaitInitialLoad()
    }

    private suspend fun pollSmsIfDue() {
        val intervalMinutes = settingsRepository.smsPollIntervalMinutes.value
        if (intervalMinutes <= 0) return // 0 = Never
        // Persisted backoff: compare against max(lastSync, lastAttempt) so a
        // failing poll (or one skipped for permission) waits out the interval instead of
        // retrying every 60s tick.
        val now = System.currentTimeMillis()
        val state = smsStore.getSyncStateOnce()
        val lastActivityMs = maxOf(state.lastSyncEpochMs, state.lastAttemptEpochMs)
        if (now - lastActivityMs < intervalMinutes * 60_000L) return
        smsPoller.poll()
    }

    /**
     * Email poll gate: one interval setting for all accounts, but backoff is evaluated
     * per account (each account's sync state carries its own lastSync/lastAttempt), so a
     * failing or recently-synced account never forces an IMAP connection for the others.
     */
    private suspend fun pollEmailsIfDue() {
        val intervalMinutes = settingsRepository.emailPollIntervalMinutes.value
        if (intervalMinutes <= 0) return // 0 = Never
        val now = System.currentTimeMillis()
        val intervalMs = intervalMinutes * 60_000L
        val dueAccounts = settingsRepository.emailAccounts.value.filter { account ->
            // A brand-new account (never polled) has lastActivityMs = 0 and is always due.
            val state = emailStore.getSyncStateOnce(account.id)
            val lastActivityMs = maxOf(state.lastSyncEpochMs, state.lastAttemptEpochMs)
            now - lastActivityMs >= intervalMs
        }
        if (dueAccounts.isNotEmpty()) emailPoller.poll(dueAccounts) // per-account gate, not all-or-nothing
    }

    private class HeartbeatSnapshot(
        val prompt: String,
        val smsIds: List<Long>,
        val notificationKeys: List<String>,
        /** Composite (accountId, uid) keys — a bare uid is never unique across accounts. */
        val emailKeys: List<Pair<String, Long>>,
    )

    // Runs exactly one heartbeat, guarded against overlap (loop vs manual trigger).
    private suspend fun executeHeartbeat() {
        if (!heartbeatInFlight.compareAndSet(false, true)) return
        try {
            runHeartbeat()
        } finally {
            heartbeatInFlight.set(false)
        }
    }

    // This method is called from runCycle
    private suspend fun runHeartbeat() {
        // Resolve the heartbeat conversation: the user-selected one, or the dedicated
        // auto-created conversation when "Auto (dedicated)" is selected. The model setting
        // always applies as an override; null inherits the conversation's model.
        val userSelectedId = settingsRepository.heartbeatConversationId.value
        val heartbeatConversationId: String =
            if (!userSelectedId.isNullOrBlank()) {
                // Orphaned pin heal (verified against a real device run, 2026-10-03: three
                // consecutive "Conversation not found" failures after the pinned conversation
                // was deleted). Verify-then-clear: if the pinned conversation no longer
                // exists, clear the setting and fall back to the dedicated auto-created one
                // instead of failing every tick against a dead id.
                val exists = getConversationRepository().getConversation(userSelectedId) != null
                if (exists) {
                    userSelectedId
                } else {
                    settingsRepository.saveHeartbeatConversationId(null)
                    com.newoether.agora.util.DebugLog.w(
                        "HeartbeatScheduler",
                        "Pinned heartbeat conversation missing; cleared setting",
                    )
                    getConversationRepository().getOrCreateHeartbeatConversationId(
                        modelId = settingsRepository.heartbeatModel.value,
                    )
                }
            } else {
                getConversationRepository().getOrCreateHeartbeatConversationId(
                    modelId = settingsRepository.heartbeatModel.value,
                )
            }
        val modelOverride: String? = settingsRepository.heartbeatModel.value

        val snapshot = buildPrompt(heartbeatConversationId)

        val result = taskExecutionEngine.runOnce(
            conversationId = heartbeatConversationId,
            userText = snapshot.prompt,
            modelId = modelOverride,
            requestKind = "heartbeat",
            // Heartbeat owns its notification policy (see section 5 of the contract): the
            // generic "Response ready: HEARTBEAT_OK" push must never fire for a clean check.
            suppressTerminalNotification = true,
        )

        // Busy = the conversation lease was taken (e.g. user actively chatting in the heartbeat
        // conversation). Touch nothing — no watermark advance, no log row, no notification —
        // so the 60s loop retries naturally and "Recent runs" never fills with busy noise.
        if (result is TaskExecutionEngine.Result.Busy) return

        val success = result is TaskExecutionEngine.Result.Success

        // Consume exactly the snapshot the AI just saw — and only on success, so a failed
        // run (or messages that arrived during the call) survive to the next heartbeat.
        if (success) {
            if (snapshot.smsIds.isNotEmpty()) {
                smsStore.removePending(snapshot.smsIds)
            }
            if (snapshot.notificationKeys.isNotEmpty()) {
                notificationStore.removePending(snapshot.notificationKeys)
            }
            if (snapshot.emailKeys.isNotEmpty()) {
                // One transaction: delete the delivered rows AND advance each account's
                // watermark past them, so the user's own check_email never repeats what the
                // heartbeat showed and a crash cannot leave the two out of step.
                emailStore.consumePending(snapshot.emailKeys)
            }
        }
        notificationStore.performRetentionSweep()
        try {
            taskConfirmationStore.cleanupOld()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Retention is housekeeping; it must never skip the watermark/log bookkeeping below.
            com.newoether.agora.util.DebugLog.w("HeartbeatScheduler", "Task confirmation cleanup failed", e)
        }

        // Surfacing runs after consume-on-success and the retention sweep (see the helper).
        if (success) {
            surfaceTaskConfirmation(heartbeatConversationId, result)
        }

        // Log the result. Failure advances the watermark too: without it, a persistently
        // failing provider would be retried every 60s tick instead of waiting out the interval.
        val resultText = when (result) {
            is TaskExecutionEngine.Result.Success -> result.text
            is TaskExecutionEngine.Result.Failure -> result.reason
            is TaskExecutionEngine.Result.Busy -> ""
        }
        heartbeatManager.setLastHeartbeatEpochMs(System.currentTimeMillis())

        // Record the outcome in Room (kept to the 5 newest rows).
        heartbeatManager.recordHeartbeat(success, if (success) null else resultText.ifBlank { null })

        // Send push notification if backgrounded
        if (!appForegroundTracker.isInForeground && !success) {
            sendHeartbeatNotification(resultText)
        }
    }

    /**
     * Rich task confirmations (plan PLAN-20261002-TASK-CONFIRM): surfaces the result when the
     * toggle is ON AND the result is actionable. Runs AFTER the snapshot consume-on-success and
     * the retention sweeps so the pre-feature semantics are untouched. "Actionable" is decided
     * HERE — the store never sees sentinels: the common clean-heartbeat result is HEARTBEAT_OK,
     * which the user explicitly does not want surfaced.
     *
     * Best-effort by contract: the snapshot is already consumed and the run already succeeded,
     * so a staging/posting failure must NOT skip the watermark advance and the run log below —
     * otherwise the 60s loop would re-run (and re-bill) a heartbeat that already completed.
     */
    private suspend fun surfaceTaskConfirmation(
        conversationId: String,
        result: TaskExecutionEngine.Result.Success,
    ) {
        try {
            if (!settingsRepository.taskConfirmationEnabled.value) return
            val actionable = extractActionableHeartbeatText(result.text) ?: return
            val plainBody = TaskConfirmationStore.plainTextForConfirmation(actionable)
            // Markdown-only remainders ("###") collapse to empty; the store rejects blank bodies.
            if (plainBody.isBlank()) return
            // PROMPT/AUTO branching lives in one place shared with tasks. Heartbeat specifics:
            // modelMessageId (not runId) is the dedup identity — Result.Success only exposes
            // (modelMessageId, text) and it is unique per generation; the durable title is the
            // neutral "Heartbeat" (surfaces localize their own header), and AUTO passes a blank
            // title so displayTitleFor localizes it without a Context lookup here.
            stageAndNotifyTaskConfirmation(
                settings = settingsRepository,
                store = taskConfirmationStore,
                notifier = taskPromptNotifier,
                appInForeground = appForegroundTracker.isInForeground,
                source = TaskConfirmationSource.HEARTBEAT,
                title = if (settingsRepository.taskConfirmationMode.value == TaskConfirmationMode.AUTO) {
                    ""
                } else {
                    "Heartbeat"
                },
                conversationId = conversationId,
                modelMessageId = result.modelMessageId,
                plainBody = plainBody,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            com.newoether.agora.util.DebugLog.e("HeartbeatScheduler", "Task confirmation surfacing failed", e)
        }
    }

    private suspend fun buildPrompt(heartbeatConversationId: String): HeartbeatSnapshot {
        val customPrompt = settingsRepository.heartbeatPrompt.value
        val pendingSms = smsStore.getPendingSnapshot()
        val pendingNotifications = notificationStore.getPendingSnapshot()
        // Rows of an account that is no longer connected (removed, or dropped by a
        // replace-restore) are never delivered to the model.
        val pendingEmails = emailStore.getPendingSnapshot().let { pending ->
            if (pending.isEmpty()) {
                pending
            } else {
                val connectedIds = settingsRepository.emailAccounts.value.map { it.id }.toSet()
                pending.filter { it.accountId in connectedIds }
            }
        }

        // Bounded tail query: only final model responses, newest first. Never load the full
        // message graph for this — the heartbeat conversation grows without bound.
        val recentResponses = getConversationRepository()
            .getRecentFinalModelResponses(heartbeatConversationId, limit = 3)
            .map { it.text }

        val builder = com.newoether.agora.data.HeartbeatPromptBuilder(
            taskManager = getTaskManager(),
            loopManager = getLoopManager(),
            conversationRepository = getConversationRepository(),
            memoryManager = getMemoryManager(),
            taskRepository = getTaskRepository(),
        )
        return HeartbeatSnapshot(
            prompt = builder.buildHeartbeatPrompt(
                customPrompt = customPrompt,
                pendingSms = pendingSms,
                pendingNotifications = pendingNotifications,
                pendingEmails = pendingEmails,
                recentResponses = recentResponses,
            ),
            smsIds = pendingSms.map { it.id },
            notificationKeys = pendingNotifications.map { it.id },
            emailKeys = pendingEmails.map { it.accountId to it.uid },
        )
    }

    private fun getTaskRepository(): com.newoether.agora.data.repository.TaskRepository {
        val application = appContext.applicationContext as com.newoether.agora.AgoraApplication
        return application.requireContainer().taskRepository
    }

    private fun getTaskManager(): TaskManager {
        val application = appContext.applicationContext as com.newoether.agora.AgoraApplication
        return application.requireContainer().taskManager
    }

    private fun getLoopManager(): LoopManager {
        val application = appContext.applicationContext as com.newoether.agora.AgoraApplication
        return application.requireContainer().loopManager
    }

    private fun getConversationRepository(): ConversationRepository {
        val application = appContext.applicationContext as com.newoether.agora.AgoraApplication
        return application.requireContainer().conversationRepository
    }

    private fun getMemoryManager(): com.newoether.agora.data.MemoryManager {
        val application = appContext.applicationContext as com.newoether.agora.AgoraApplication
        return application.requireContainer().memoryManager
    }

    private suspend fun sendHeartbeatNotification(message: String) {
        heartbeatNotifier.sendHeartbeatNotification("Heartbeat Check", message)
    }

    /**
     * The actionable remainder of a heartbeat result, or null when the result is silent
     * (plan PLAN-20261002-TASK-CONFIRM decision 8). Silent means: empty after trim, or
     * exactly [HeartbeatManager.HEARTBEAT_OK_SENTINEL] case-insensitive — the value the
     * heartbeat prompt asks the model to emit when nothing needs attention. A result that
     * STARTS with the sentinel followed by substantive content (e.g.
     * "HEARTBEAT_OK — el backup lleva 3 días fallando") gets the prefix and its immediate
     * separator stripped, so a formal prefix never swallows real content.
     */
    internal fun extractActionableHeartbeatText(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val sentinel = HeartbeatManager.HEARTBEAT_OK_SENTINEL
        // Models routinely decorate the marker ("**HEARTBEAT_OK**", "`HEARTBEAT_OK`",
        // "HEARTBEAT_OK."); decoration is not content, so it must not defeat the silent path.
        val unwrapped = trimmed.trimStart(*DECORATION_CHARS)
        if (unwrapped.regionMatches(0, sentinel, 0, sentinel.length, ignoreCase = true)) {
            val next = unwrapped.getOrNull(sentinel.length)
            // "HEARTBEAT_OKAY…" is ordinary text that merely starts with the same letters.
            if (next == null || !(next.isLetterOrDigit() || next == '_')) {
                val remainder = unwrapped.substring(sentinel.length)
                    .trimStart(*DECORATION_CHARS, '—', '–', '-', ':', '.', '!', ',', ';')
                    .trim()
                // Nothing substantive left (e.g. "HEARTBEAT_OK.") → silent.
                return remainder.takeIf { r -> r.any { it.isLetterOrDigit() } }
            }
        }
        return trimmed
    }
}