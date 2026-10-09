package com.newoether.agora.automation

import com.newoether.agora.data.TaskConfirmationStore
import com.newoether.agora.data.local.TaskConfirmationMode
import com.newoether.agora.data.local.TaskConfirmationSource
import com.newoether.agora.data.local.TaskConfirmationStatus
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.service.TaskPromptNotifier

/**
 * Single owner of the PROMPT-vs-AUTO branching for a result that is ALREADY decided to be
 * actionable and already projected to plain text. Two genuine consumers (the heartbeat
 * scheduler and `AppContainer.surfaceAutomationResult` for tasks) used to carry byte-for-byte
 * copies of this logic; callers keep only what differs (the enable toggle, the heartbeat
 * sentinel filter, the blank-body check).
 *
 *  - AUTO: informational notification only, background-gated; NOTHING is staged.
 *  - PROMPT: always stages (the chat banner surfaces the row in the foreground); the heads-up
 *    post is background-gated and only for a row that is still PENDING, so a re-staged dedup
 *    row that was already resolved never comes back as a prompt with dead actions.
 */
internal suspend fun stageAndNotifyTaskConfirmation(
    settings: SettingsRepository,
    store: TaskConfirmationStore,
    notifier: TaskPromptNotifier,
    appInForeground: Boolean,
    source: TaskConfirmationSource,
    title: String,
    conversationId: String,
    modelMessageId: String?,
    plainBody: String,
) {
    val background = !appInForeground && notifier.canPost()
    if (settings.taskConfirmationMode.value == TaskConfirmationMode.AUTO) {
        if (background) {
            // AUTO has no durable row — a refused post leaves nothing to retry; the
            // structured log inside postInfo is the only trace.
            notifier.postInfo(
                sourceType = source.name,
                title = title,
                body = plainBody,
                conversationId = conversationId,
                modelMessageId = modelMessageId,
            )
        }
        return
    }
    val staged = store.stage(
        TaskConfirmationStore.Draft(
            source = source,
            conversationId = conversationId,
            modelMessageId = modelMessageId,
            title = title,
            bodyText = plainBody,
        ),
    )
    if (background && staged.status == TaskConfirmationStatus.PENDING.name) {
        val posted = notifier.post(
            confirmationId = staged.id,
            sourceType = staged.sourceType,
            rowTitle = staged.title,
            body = staged.bodyText,
            conversationId = staged.conversationId,
        )
        // P2 adapted headless: notifications were blocked at staging. The banner row is
        // the only visible surface; arm the deferred re-post so the prompt surfaces the
        // moment the user unblocks notifications, instead of never.
        if (!posted) {
            notifier.schedulePostRetry(staged.id)
        }
    }
}
