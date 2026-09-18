package com.newoether.agora.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.AgoraApplication
import com.newoether.agora.MainActivity
import com.newoether.agora.R
import com.newoether.agora.automation.TaskExecutionEngine
import com.newoether.agora.data.DEFAULT_COLOR_SCHEME
import com.newoether.agora.data.DEFAULT_DYNAMIC_COLOR
import com.newoether.agora.data.DEFAULT_SCHEME_STYLE
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.ui.chat.message.COMPOSER_ICON_CROSSFADE_DURATION_MS
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.newoether.agora.ui.motion.ProvideAgoraMotionPolicy
import com.newoether.agora.ui.theme.AgoraTheme
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.theme.ColorSchemePreset
import com.newoether.agora.ui.theme.SchemeStyle
import com.newoether.agora.ui.theme.ThemeMode
import com.newoether.agora.util.DebugLog
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Shared state + send logic for the assistant overlay, hosted by either
 * [AgoraVoiceInteractionSession] (stock Android: overlay window with Assist API screen context)
 * or [AssistantActivity] (OEMs like Samsung One UI whose assistant picker only lists
 * ACTION_ASSIST activities; no Assist API data there). Both hosts route sends through the
 * ordinary headless pipeline (`TaskExecutionEngine.runOnce`) — see
 * `development/system-assistant.md`.
 */
class AssistantOverlayState(
    private val context: Context,
    private val scope: CoroutineScope,
    private val close: () -> Unit,
) {
    var prompt by mutableStateOf("")
    var status by mutableStateOf(Status.Idle)
        private set
    var responseText by mutableStateOf("")
        private set
    var conversationId by mutableStateOf<String?>(null)
        private set

    /** Screen context; only the VoiceInteractionSession host populates these. */
    var screenText by mutableStateOf<String?>(null)
    var screenshotPath by mutableStateOf<String?>(null)
    var useScreenContext by mutableStateOf(true)

    var isListening by mutableStateOf(false)
        private set
    var voiceEnabled by mutableStateOf(true)
        private set

    /** Live voice-to-voice gate (Settings toggle, default off). */
    var voiceToVoiceEnabled by mutableStateOf(false)
        private set

    /** Prompt text that predates the current dictation; spoken results replace only the
     *  dictated segment so text the user typed before tapping the mic survives. */
    private var dictationPrefix: String? = null

    val voiceInput by lazy {
        AssistantVoiceInput(
            context = context,
            onPartial = { prompt = joinDictationPrompt(dictationPrefix, it) },
            onFinal = { prompt = joinDictationPrompt(dictationPrefix, it); stopListening() },
            onError = { stopListening() },
        )
    }

    enum class Status { Idle, Generating, Done, Error, Busy }

    /** Reads the voice-input toggle once the container is available. */
    fun loadSettings() {
        scope.launch {
            val container =
                (context.applicationContext as AgoraApplication).awaitContainer() ?: return@launch
            voiceEnabled = container.settingsManager.assistantTools.voiceInputEnabled.first()
            voiceToVoiceEnabled = container.settingsManager.assistantTools.voiceToVoiceEnabled.first()
            // Screen-context screenshots are staged on disk before the user decides whether to
            // attach them. Enqueue an attachment reconcile so any copy that never became
            // message-owned (chip off, closed without sending, screenshot landing after the
            // send) is reclaimed through the ordinary orphan sweep.
            container.conversationRepository.scheduleAttachmentReconcile()
        }
    }

    /**
     * Voice-to-voice entry (plan Phase 4 §10.5): closes the ephemeral session/overlay window and
     * opens the in-app call screen — the microphone foreground service needs a real foreground
     * activity, which a VoiceInteractionSession window cannot host.
     */
    fun startVoiceCall() {
        val intent = Intent(context, com.newoether.agora.ui.assistant.VoiceModeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        close()
    }

    /** Mic toggle: requests RECORD_AUDIO through the trampoline on first use, then starts or
     *  stops dictation. Results only fill the prompt — sending stays manual. */
    fun toggleMic() {
        if (isListening) {
            stopListening()
            return
        }
        if (!voiceInput.isAvailable) {
            DebugLog.w(TAG, "No speech recognition service available")
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            context.startActivity(
                Intent(context, AssistantVoicePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        dictationPrefix = if (prompt.isEmpty()) null else prompt
        voiceInput.start()
        isListening = true
    }

    fun stopListening() {
        isListening = false
        dictationPrefix = null
        voiceInput.destroy()
    }

    fun send() {
        val text = prompt.trim()
        if (text.isEmpty() || status == Status.Generating) return
        stopListening()
        status = Status.Generating
        responseText = ""
        scope.launch {
            when (val result = runGeneration(text)) {
                is TaskExecutionEngine.Result.Success -> {
                    responseText = result.text
                    status = Status.Done
                }
                is TaskExecutionEngine.Result.Busy -> {
                    // Not a failure: the ordinary pipeline is mid-run elsewhere. The prompt
                    // stays editable so the user can retry once that run settles.
                    responseText = context.getString(R.string.assistant_overlay_busy)
                    status = Status.Busy
                }
                is TaskExecutionEngine.Result.Failure -> {
                    responseText = context.getString(R.string.assistant_overlay_error)
                    // Failure reasons can embed message text; never log them raw
                    // (privacy-safe logging contract, same as the heartbeat fix).
                    DebugLog.w(TAG, "Assistant generation failed")
                    status = Status.Error
                }
            }
        }
    }

    /** Clears a finished activation's surface state when the singleTask [AssistantActivity] is
     *  re-invoked by the assistant gesture. A generation still in flight keeps its UI so the
     *  user sees the result; anything already terminal starts fresh, like a new activity. */
    fun resetForNewActivation() {
        if (status == Status.Generating) return
        stopListening()
        prompt = ""
        status = Status.Idle
        responseText = ""
        conversationId = null
        useScreenContext = true
    }

    /**
     * Creates (or reuses, per the Settings toggle) the assistant conversation and runs one
     * ordinary generation. Returns the engine outcome unchanged so the UI can distinguish
     * Busy (retryable, prompt preserved) from real failures.
     */
    private suspend fun runGeneration(text: String): TaskExecutionEngine.Result = withContext(Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext as AgoraApplication
            val container = app.awaitContainer()
                ?: return@runCatching TaskExecutionEngine.Result.Failure("App container not ready")
            val settings = container.settingsManager
            val repo = container.conversationRepository

            val reuse = settings.assistantTools.reuseConversationEnabled.first()
            val existing = if (reuse) repo.getConversationByOrigin(ORIGIN) else null
            val convId = existing?.id ?: UUID.randomUUID().toString()
            if (existing == null) {
                // `settings.selectedModel` is a legacy cold-start placeholder
                // (Constants.EXAMPLE_MODEL_ID) that the interactive chat UI no longer keeps in
                // sync — it resolves models through ConversationWorkspaceStore instead, always
                // validated against enabledModels. This is the one caller that creates a
                // conversation with no explicit modelId, so it must do that same validation
                // itself; otherwise TaskExecutionEngine's fallback chain forwards the stale
                // placeholder straight to the provider layer (surfaces as an unresolvable-host
                // network error on a fresh/never-touched install).
                val enabled = settings.enabledModels.first()
                val resolvedModelId = settings.selectedModel.first().takeIf { it in enabled }
                    ?: enabled.firstOrNull()
                repo.upsertConversation(
                    ChatEntity(
                        id = convId,
                        title = context.getString(R.string.assistant_conversation_title),
                        origin = ORIGIN,
                        modelId = resolvedModelId,
                    )
                )
            }
            conversationId = convId

            // Screen context: honored only when the user kept the chip on AND the corresponding
            // Settings toggle allows it. Text goes inline in the prompt; the screenshot rides as
            // an ordinary image attachment so any multimodal provider can consume it.
            val attachScreenshot = settings.assistantTools.attachScreenshotEnabled.first()
            val includeText = settings.assistantTools.includeScreenTextEnabled.first()
            val effectiveText = buildString {
                append(text)
                if (useScreenContext && includeText) {
                    screenText?.takeIf { it.isNotBlank() }?.let { screen ->
                        append("\n\n--- Screen content ---\n").append(screen)
                    }
                }
            }
            val imagePath = if (useScreenContext && attachScreenshot) screenshotPath else null
            val attachmentMeta = imagePath?.let { path ->
                val file = File(path)
                Json.encodeToString(
                    AttachmentMeta.serializer(),
                    AttachmentMeta(
                        items = listOf(
                            AttachmentItem(
                                originalUri = path,
                                type = "image",
                                fileName = file.name,
                                mimeType = "image/png",
                                imageIndex = 0,
                                fileSize = file.length(),
                            )
                        )
                    ),
                )
            }

            container.taskExecutionEngine.runOnce(
                conversationId = convId,
                userText = effectiveText,
                requestKind = "assistant",
                images = listOfNotNull(imagePath),
                attachmentMeta = attachmentMeta,
            )
        }.getOrElse { error ->
            DebugLog.w(TAG, "Assistant generation failed: ${error.message}")
            TaskExecutionEngine.Result.Failure(error.message ?: "Assistant generation failed")
        }
    }

    fun openInApp() {
        val id = conversationId ?: return
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_CONVERSATION_ID, id)
        context.startActivity(intent)
        close()
    }

    fun close() = close.invoke()

    companion object {
        const val TAG = "AssistantOverlay"
        const val ORIGIN = "assistant"

        /** Prepends text the user had typed before dictating; partials replace only the
         *  dictated segment so live edits converge on the spoken text without losing the prefix. */
        fun joinDictationPrompt(prefix: String?, dictated: String): String {
            val base = prefix?.trimEnd() ?: return dictated
            if (base.isEmpty()) return dictated
            return base + " " + dictated
        }
    }
}

/** The overlay UI shared by both hosts. Mirrors the chat composer's visual language
 *  (borderless elevated input, pill controls, circular send button) — see ChatBottomBar. */
@Composable
fun AssistantOverlayContent(state: AssistantOverlayState) {
    val generating = state.status == AssistantOverlayState.Status.Generating
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = stringResource(R.string.assistant_overlay_title),
            style = MaterialTheme.typography.titleMedium,
        )
        TextField(
            value = state.prompt,
            onValueChange = { state.prompt = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            placeholder = {
                Text(
                    stringResource(
                        if (state.isListening) R.string.assistant_overlay_listening
                        else R.string.assistant_overlay_hint
                    ),
                    style = ChatType.input,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            },
            enabled = !generating,
            maxLines = 6,
            shape = RoundedCornerShape(20.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                disabledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            textStyle = ChatType.input.copy(color = MaterialTheme.colorScheme.onSurface),
        )
        if (state.screenText != null || state.screenshotPath != null) {
            FilterChip(
                selected = state.useScreenContext,
                onClick = { state.useScreenContext = !state.useScreenContext },
                label = { Text(stringResource(R.string.assistant_overlay_use_screen)) },
                modifier = Modifier.padding(top = 8.dp),
                enabled = !generating,
            )
        }
        if (state.status == AssistantOverlayState.Status.Done ||
            state.status == AssistantOverlayState.Status.Busy ||
            state.status == AssistantOverlayState.Status.Error
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(
                    text = state.responseText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (state.status) {
                        AssistantOverlayState.Status.Error -> MaterialTheme.colorScheme.error
                        AssistantOverlayState.Status.Busy ->
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
                state.conversationId?.let {
                    TextButton(
                        onClick = state::openInApp,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.assistant_overlay_open_chat))
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(48.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceColorAtElevation(10.dp),
                        RoundedCornerShape(100),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                if (state.voiceToVoiceEnabled) {
                    IconButton(onClick = state::startVoiceCall) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = stringResource(R.string.assistant_overlay_call),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                IconButton(
                    onClick = state::toggleMic,
                    enabled = !generating && state.voiceEnabled,
                ) {
                    Icon(
                        imageVector = if (state.isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = stringResource(R.string.assistant_overlay_mic),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = state::close) {
                Text(stringResource(R.string.assistant_overlay_close))
            }
            AssistantSendButton(
                actionable = state.prompt.isNotBlank() && !generating,
                generating = generating,
                onSend = state::send,
            )
        }
    }
}

/** Circular send button mirroring the chat composer's ComposerSendButton: animated
 *  primary/surfaceVariant container, arrow-up icon crossfading into a progress indicator
 *  while generating. */
@Composable
private fun AssistantSendButton(
    actionable: Boolean,
    generating: Boolean,
    onSend: () -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = if (actionable) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(durationMillis = 400),
        label = "assistantFabContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (actionable) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 400),
        label = "assistantFabContent",
    )
    Surface(
        onClick = { if (actionable) onSend() },
        enabled = actionable,
        modifier = Modifier.size(46.dp),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Crossfade(
                targetState = generating,
                animationSpec = tween(
                    durationMillis = COMPOSER_ICON_CROSSFADE_DURATION_MS,
                    easing = LinearEasing,
                ),
                label = "assistantActionIcon",
            ) { busy ->
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp,
                        color = contentColor,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = stringResource(R.string.assistant_overlay_send),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/** Wraps [AgoraTheme] with the user's persisted theme settings so every assistant surface
 *  (overlay activity, voice-interaction session window, voice call screen) matches the app's
 *  configured appearance instead of AgoraTheme's defaults. Mirrors the collection in
 *  MainActivity; settings flows are live, so theme changes apply without restarting the host.
 *  Also provides the Agora motion policy so reduce-motion is honored on assistant surfaces
 *  exactly like in the main app. */
@Composable
fun AssistantAppTheme(content: @Composable () -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val settingsManager = remember(appContext) { SettingsManager(appContext) }
    val themeMode by settingsManager.themeMode.collectAsState(initial = "FOLLOW_DEVICE")
    val amoledEnabled by settingsManager.amoledEnabled.collectAsState(initial = false)
    val colorSchemeName by settingsManager.colorScheme.collectAsState(initial = DEFAULT_COLOR_SCHEME)
    val schemeStyleName by settingsManager.schemeStyle.collectAsState(initial = DEFAULT_SCHEME_STYLE)
    val dynamicColor by settingsManager.dynamicColor.collectAsState(initial = DEFAULT_DYNAMIC_COLOR)
    val fontPreference by settingsManager.fontPreference.collectAsState(initial = "app_default")
    val customFontPath by settingsManager.customFontPath.collectAsState(initial = "")
    val reduceMotion by settingsManager.reduceMotion.collectAsState(initial = false)

    ProvideAgoraMotionPolicy(appReduceMotion = reduceMotion) {
        AgoraTheme(
        themeMode = try {
            ThemeMode.valueOf(themeMode)
        } catch (_: Exception) {
            ThemeMode.FOLLOW_DEVICE
        },
        colorSchemePreset = try {
            ColorSchemePreset.valueOf(colorSchemeName)
        } catch (_: Exception) {
            ColorSchemePreset.FOREST
        },
        schemeStyle = try {
            SchemeStyle.valueOf(schemeStyleName)
        } catch (_: Exception) {
            SchemeStyle.TONAL_SPOT
        },
        dynamicColor = dynamicColor,
        amoledEnabled = amoledEnabled,
        fontPreference = fontPreference,
        customFontPath = customFontPath,
        ) {
            content()
        }
    }
}
