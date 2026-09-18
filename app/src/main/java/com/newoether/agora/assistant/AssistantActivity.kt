package com.newoether.agora.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Assistant entry point for OEMs whose assistant picker only lists ACTION_ASSIST activities
 * (Samsung One UI, among others — stock Android uses [AgoraVoiceInteractionService] instead).
 * Launched by the system assist gesture (long-press power / corner swipe) as a translucent
 * activity so it reads as an overlay over the foreground app. No Assist API screen context is
 * available on this path; voice dictation works normally (this is a real activity, so runtime
 * permissions work directly). See `development/system-assistant.md`.
 */
class AssistantActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var state: AssistantOverlayState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = AssistantOverlayState(this, scope, ::finish)
        state.loadSettings()
        setContent {
            AssistantAppTheme {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    // Flush below the status bar (top sheet); replaces the old fixed 48dp that
                    // floated the panel lower than the device's actual status bar height.
                    modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                ) {
                    AssistantOverlayContent(state)
                }
            }
        }
    }

    override fun onPause() {
        state.stopListening()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask re-launch: the system gesture starts a fresh activation even though the
        // translucent activity survived (e.g. dismissed via Home). A generation still in
        // flight keeps its UI so the user sees its result; everything else resets.
        state.resetForNewActivation()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
