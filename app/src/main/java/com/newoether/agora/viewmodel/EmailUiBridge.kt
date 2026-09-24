package com.newoether.agora.viewmodel

import com.newoether.agora.data.EmailDraft
import com.newoether.agora.data.EmailDraftStore
import com.newoether.agora.data.EmailPoller
import com.newoether.agora.data.EmailStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

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
    private val scope: CoroutineScope,
) {
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
}
