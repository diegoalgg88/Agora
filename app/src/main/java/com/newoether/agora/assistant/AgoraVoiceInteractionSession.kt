package com.newoether.agora.assistant

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Assistant overlay session (stock Android path). Shows the shared assistant sheet over the
 * foreground app and captures screen context through the Assist API. On OEMs whose picker only
 * lists ACTION_ASSIST activities (e.g. Samsung One UI), [AssistantActivity] is the entry point
 * instead. Sending routes through the ordinary headless generation pipeline — no parallel
 * pipeline, per `development/system-assistant.md`.
 */
class AgoraVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owners = SessionOwners()
    private val state = AssistantOverlayState(context, scope, ::finish)

    override fun onHandleAssist(
        data: Bundle?,
        structure: android.app.assist.AssistStructure?,
        content: android.app.assist.AssistContent?,
    ) {
        super.onHandleAssist(data, structure, content)
        val text = AssistContextCapture.extractText(structure)
        if (text.isNotEmpty()) state.screenText = text
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (screenshot == null) return
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                AssistContextCapture.saveScreenshot(context, screenshot)
            }
            if (path != null) state.screenshotPath = path
        }
    }

    override fun onCreateContentView(): View {
        owners.start()
        state.loadSettings()
        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(owners)
            setViewTreeViewModelStoreOwner(owners)
            setViewTreeSavedStateRegistryOwner(owners)
            setContent {
                AssistantAppTheme {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        // Same status-bar flush as AssistantActivity; a session window renders
                        // under the status bar on devices that grant it full-screen height.
                        modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                    ) {
                        AssistantOverlayContent(state)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        state.stopListening()
        scope.cancel()
        owners.destroy()
        super.onDestroy()
    }
}

/** Minimal Lifecycle/ViewModelStore/SavedState owners so Compose works in a session window
 * that has no hosting Activity. */
private class SessionOwners : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun start() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
