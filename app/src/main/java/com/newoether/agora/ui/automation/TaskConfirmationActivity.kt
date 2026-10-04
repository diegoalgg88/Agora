package com.newoether.agora.ui.automation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.newoether.agora.AgoraApplication
import com.newoether.agora.MainActivity
import com.newoether.agora.assistant.AssistantAppTheme
import com.newoether.agora.data.local.TaskConfirmationCardStyle
import com.newoether.agora.data.local.TaskConfirmationEntity
import com.newoether.agora.data.local.TaskConfirmationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Translucent activity showing one rich task confirmation as a card anchored at the bottom
 * or centered, per the user's style setting (plan PLAN-20261002-TASK-CONFIRM). The window
 * theme derives from the proven assistant overlay (translucent + light dim); the card
 * layout pattern is Universal Installer's DialogInstallActivity — not the assistant
 * overlay, whose contract this must not grow.
 *
 * Zombie-window guard: the row is re-read at launch (and on every new intent, because the
 * activity is singleTask); if it is already resolved — another surface consumed it first —
 * and nothing else is showing, the activity finishes without rendering anything.
 * Resolutions (Confirm / Dismiss / Snooze) consume the row via the store, cancel the
 * notification, and finish. "Open conversation" navigates through MainActivity's
 * EXTRA_CONVERSATION_ID and leaves the confirmation PENDING for explicit resolution.
 */
class TaskConfirmationActivity : ComponentActivity() {

    private val rowState = MutableStateFlow<TaskConfirmationEntity?>(null)
    private val cardStyleState = MutableStateFlow(TaskConfirmationCardStyle.DEFAULT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AssistantAppTheme {
                val row by rowState.collectAsState()
                val style by cardStyleState.collectAsState()
                val centered = style == TaskConfirmationCardStyle.CENTERED
                // Card anchor honors the user's style setting — Universal Installer's
                // InstallUiStyle analogue: bottom sheet vs centered dialog. Same card
                // content; only the anchor (and the centered max width) changes. The nav-bar
                // inset matters: targetSdk 36 is edge-to-edge, so a bottom card would
                // otherwise sit under the gesture/navigation bar.
                // Tap on the scrim closes the card (row stays PENDING, like Back). The card's
                // Surface consumes its own pointer input, so taps on it never reach this.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { finish() }
                        .navigationBarsPadding()
                        .imePadding(),
                    contentAlignment = if (centered) Alignment.Center else Alignment.BottomCenter,
                ) {
                    row?.let { entity ->
                        TaskConfirmationCard(
                            row = entity,
                            centered = centered,
                            onConfirm = { resolve(entity, acknowledge = true) },
                            onDismiss = { resolve(entity, acknowledge = false) },
                            onSnooze = { minutes -> snooze(entity, minutes) },
                            onOpenConversation = { openConversation(entity.conversationId) },
                        )
                    }
                }
            }
        }
        load(intent)
    }

    /**
     * singleTask: a second notification tap while this activity is alive arrives here, not in
     * onCreate. Without re-loading, the card would keep showing the previous row while the
     * user tapped a different one.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(intent)
    }

    private fun load(source: Intent) {
        val id = source.getStringExtra(EXTRA_CONFIRMATION_ID)
        val app = application as? AgoraApplication
        if (id.isNullOrBlank() || app == null) {
            finishIfNothingShown()
            return
        }
        lifecycleScope.launch {
            val container = app.awaitContainer() ?: run {
                finishIfNothingShown()
                return@launch
            }
            // Cold start from a notification: the settings StateFlow holds its default until the
            // DataStore load completes, which would silently ignore a saved CENTERED choice.
            container.settingsRepository.awaitInitialLoad()
            cardStyleState.value = container.settingsRepository.taskConfirmationCardStyle.value
            val current = container.taskConfirmationStore.get(id)
            if (current == null || current.status != TaskConfirmationStatus.PENDING.name) {
                finishIfNothingShown()
                return@launch
            }
            rowState.value = current
        }
    }

    /** A stale intent must not close a card the user is currently looking at. */
    private fun finishIfNothingShown() {
        if (rowState.value == null) finish()
    }

    private fun resolve(row: TaskConfirmationEntity, acknowledge: Boolean) {
        val app = application as? AgoraApplication ?: return
        lifecycleScope.launch {
            val container = app.awaitContainer() ?: return@launch
            if (acknowledge) {
                container.taskConfirmationStore.acknowledge(row.id)
            } else {
                container.taskConfirmationStore.dismiss(row.id)
            }
            // Cancel regardless of the single-winner result: if another surface consumed the
            // row first, this notification must not linger as a dead button.
            container.taskPromptNotifier.cancel(row.id)
            finish()
        }
    }

    private fun snooze(row: TaskConfirmationEntity, minutes: Int) {
        val app = application as? AgoraApplication ?: return
        lifecycleScope.launch {
            val container = app.awaitContainer() ?: return@launch
            if (container.taskConfirmationStore.snooze(row.id, minutes)) {
                // The notification goes away now; a one-shot alarm (daemon-independent) re-posts
                // it when due. Arm AFTER cancel: cancel only touches the notification.
                container.taskPromptNotifier.cancel(row.id)
                container.taskPromptNotifier.scheduleReminder(
                    row.id,
                    System.currentTimeMillis() + minutes * 60_000L,
                )
            }
            finish()
        }
    }

    private fun openConversation(conversationId: String) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
        )
        // The row stays PENDING (the chat banner keeps it resolvable) but this translucent card
        // must not linger behind the conversation and reappear from Recents.
        finish()
    }

    companion object {
        const val EXTRA_CONFIRMATION_ID = "confirmation_id"
    }
}
