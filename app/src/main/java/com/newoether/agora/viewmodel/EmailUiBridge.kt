package com.newoether.agora.viewmodel

import com.newoether.agora.data.EmailDraft
import com.newoether.agora.data.EmailDraftStore
import com.newoether.agora.data.EmailPoller
import com.newoether.agora.data.EmailStore
import com.newoether.agora.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Email UI surface owned by [ChatViewModel]: the draft flow the review banner renders,
 * the pending count the email settings section shows, and the user-triggered actions.
 * Extracted from ChatViewModel so the ViewModel stays within the 999-line source
 * policy instead of accumulating email responsibilities.
 */
class EmailUiBridge(
    private val draftStore: EmailDraftStore,
    private val emailStore: EmailStore,
    private val emailPoller: EmailPoller,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {
    init {
        // Once per process (the store guards re-entry): drafts stranded in SENDING by a
        // dead process become FAILED so the user decides, instead of a stuck spinner.
        scope.launch { draftStore.recoverInterruptedDrafts() }
    }

    /** Current drafts (PENDING/SENDING/FAILED visible in the review banner). */
    val emailDrafts: Flow<List<EmailDraft>> = draftStore.drafts

    /** Pending email count for the email settings section. */
    val emailPendingCount: Flow<Int> = emailStore.pendingCount

    /** User-triggered send from the review banner — the only path that dispatches email. */
    fun sendEmailDraft(draftId: String) {
        scope.launch { draftStore.sendDraft(draftId) }
    }

    fun discardEmailDraft(draftId: String) {
        scope.launch { draftStore.removeDraft(draftId) }
    }

    /** One-shot poll for the "Refresh now" action in email settings. */
    fun refreshEmailNow() {
        scope.launch { emailPoller.poll() }
    }

    /**
     * Account teardown (development/email.md §8 cascade): removes the account and its
     * password from DataStore first so no new poll/send can pick it up, then deletes its
     * pending queue, messages, sync state and drafts from Room in one transaction. Runs
     * non-cancellable so leaving the screen mid-removal cannot strand half the cascade.
     */
    fun removeAccount(accountId: String) {
        scope.launch {
            withContext(NonCancellable) {
                settingsRepository.settingsManager.emailAccountSettings.removeAccount(accountId)
                emailStore.removeAccountData(accountId)
            }
        }
    }
}
