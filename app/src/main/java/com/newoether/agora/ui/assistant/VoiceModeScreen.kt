package com.newoether.agora.ui.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.assistant.AssistantAppTheme
import com.newoether.agora.assistant.live.LiveCallState
import com.newoether.agora.assistant.live.LiveTranscriptLog
import com.newoether.agora.assistant.live.LiveVoiceForegroundService
import com.newoether.agora.assistant.live.LiveVoiceSessionController
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator

/**
 * Full-screen voice-to-voice call (plan Phase 4 §10.5): starts the microphone foreground
 * service (foreground only — a `microphone`-type FGS may never start from background), then the
 * [LiveVoiceSessionController]. Hang-up ends the call, stops the service and finishes. The
 * transcript persists as ordinary messages in the "assistant-voice" conversation.
 *
 * Visual language follows Agora's themes: surfaces from the active color scheme, a
 * primary-tinted ambient glow behind the audio orb, and motion that honors the
 * reduce-motion policy (static orb fallback, no breathing halo). Phase 4.1 live captions
 * render the transcript log in the reserved top area.
 */
class VoiceModeActivity : ComponentActivity() {

    private var controller: LiveVoiceSessionController? = null
    private var pendingStart = false

    private val transcriptLog = LiveTranscriptLog()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            if (pendingStart) startCall() else finish()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AssistantAppTheme {
                VoiceModeScreen(
                    state = callState,
                    muted = muted,
                    echoHint = echoHint,
                    errorMessage = errorMessage,
                    audioLevel = audioLevel,
                    transcriptLog = transcriptLog,
                    showUserCaption = callState == LiveCallState.ACTIVE,
                    // Reads transcriptVersion so transcript mutations recompose this scope.
                    transcriptVersion = transcriptVersion,
                    onToggleMute = ::toggleMute,
                    onHangUp = ::hangUp,
                )
            }
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCall()
        } else {
            pendingStart = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startCall() {
        pendingStart = false
        LiveVoiceForegroundService.start(this)
        controller = LiveVoiceSessionController(
            appContext = this,
            onStateChange = { state, error ->
                callState = state
                errorMessage = error
                if (state == LiveCallState.ENDED) {
                    LiveVoiceForegroundService.stop(this)
                }
            },
            onAudioLevel = { level -> audioLevel = level },
            onTranscript = { user, model ->
                transcriptLog.onTranscript(user, model)
                transcriptVersion++
            },
            onTurnCommitted = {
                transcriptLog.commitTurn()
                transcriptVersion++
            },
        ).also {
            echoHint = !it.isEchoCancellationAvailable
            it.startCall()
        }
    }

    private fun toggleMute() {
        val target = !muted
        muted = target
        controller?.setMuted(target)
    }

    private fun hangUp() {
        controller?.endCall()
        controller?.dispose()
        controller = null
        LiveVoiceForegroundService.stop(this)
        finish()
    }

    override fun onDestroy() {
        // Safety net: an un-hung-up call (e.g. process teardown) ends with the activity.
        controller?.dispose()
        controller = null
        LiveVoiceForegroundService.stop(this)
        super.onDestroy()
    }

    private var callState by mutableStateOf(LiveCallState.IDLE)
    private var muted by mutableStateOf(false)
    private var echoHint by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)
    private var audioLevel by mutableFloatStateOf(0f)

    /** Recomposition trigger for the plain [LiveTranscriptLog]; the log itself is not
     *  Compose-observable, so every transcript mutation bumps this counter. */
    private var transcriptVersion by mutableStateOf(0)
}

@Composable
private fun VoiceModeScreen(
    state: LiveCallState,
    muted: Boolean,
    echoHint: Boolean,
    errorMessage: String?,
    audioLevel: Float,
    transcriptLog: LiveTranscriptLog,
    showUserCaption: Boolean,
    transcriptVersion: Int,
    onToggleMute: () -> Unit,
    onHangUp: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        AmbientCallBackdrop {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // ── Phase 4.1: live transcript (the formerly reserved top area) ──
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LiveTranscriptPanel(
                        log = transcriptLog,
                        showUserCaption = showUserCaption,
                        version = transcriptVersion,
                    )
                }

                // ── Status headline + orb ──
                val headline = stringResource(
                    when (state) {
                        LiveCallState.IDLE, LiveCallState.CONNECTING ->
                            R.string.live_voice_connecting
                        LiveCallState.ACTIVE ->
                            if (muted) R.string.live_voice_muted else R.string.live_voice_listening
                        LiveCallState.RECONNECTING -> R.string.live_voice_reconnecting
                        LiveCallState.ENDED -> R.string.live_voice_ended
                    },
                )
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(20.dp))
                CallOrb(state = state, muted = muted, audioLevel = audioLevel)
                if (state == LiveCallState.ENDED && errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(top = 16.dp, start = 32.dp, end = 32.dp)
                            .widthIn(max = 480.dp),
                    )
                }
                if (echoHint && state == LiveCallState.ACTIVE) {
                    Text(
                        text = stringResource(R.string.live_voice_echo_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp, start = 32.dp, end = 32.dp),
                    )
                }
                Spacer(modifier = Modifier.height(28.dp))

                // ── Controls ──
                Row(
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onToggleMute,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(
                            imageVector = if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = stringResource(R.string.live_voice_mute),
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    IconButton(
                        onClick = onHangUp,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        modifier = Modifier.size(72.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = stringResource(R.string.live_voice_hang_up),
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Phase 4.1 live captions: scrolling transcript history with committed turns plus the two
 * in-flight caption lines (what the user has said so far this turn / what the model is saying).
 * The model side streams in as Gemini speaks — the visible answer the call screen previously
 * lacked. Auto-scrolls to the newest line; the user can scroll back mid-call. [version] is the
 * activity's recomposition trigger: the log itself is plain state, so this scope re-reads its
 * fields only when the version changes.
 */
@Composable
private fun LiveTranscriptPanel(
    log: LiveTranscriptLog,
    showUserCaption: Boolean,
    version: Int,
) {
    val committed = log.committedLines
    val pendingUser = if (showUserCaption) log.pendingUserLine else ""
    val pendingModel = log.pendingModelLine
    if (committed.isEmpty() && pendingUser.isEmpty() && pendingModel.isEmpty()) return
    val listState = rememberLazyListState()
    val allowScrollMotion = LocalAgoraMotionPolicy.current.allowProgrammaticScrollMotion
    LaunchedEffect(version) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            val target = listState.layoutInfo.totalItemsCount - 1
            if (allowScrollMotion) listState.animateScrollToItem(target) else listState.scrollToItem(target)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Position-based rendering: repeated identical turns are legitimate, so content keys
        // would collide — index is the stable identity for committed lines.
        items(committed.size) { index ->
            val line = committed[index]
            TranscriptLine(side = line.side, text = line.text)
        }
        if (pendingUser.isNotEmpty()) {
            item { TranscriptLine(side = LiveTranscriptLog.Side.USER, text = pendingUser) }
        }
        if (pendingModel.isNotEmpty()) {
            item { TranscriptLine(side = LiveTranscriptLog.Side.MODEL, text = pendingModel) }
        }
        item {
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * The call's central presence indicator. Three states, all theme-driven:
 * - Connecting / reconnecting: spinner centered inside a quiet primary-tinted ring.
 * - Active: audio orb — inner disc scales with the live mic level, an outer ring echoes the
 *   level with a delay and lower alpha (breathing "double-pulse"), and a soft halo breathes
 *   continuously while unmuted. Reduce-motion swaps all of it for a static disc.
 * - Ended: neutral static disc.
 */
@Composable
private fun CallOrb(state: LiveCallState, muted: Boolean, audioLevel: Float) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val allowMotion = motionPolicy.allowContinuousMotion

    val levelScale by animateFloatAsState(
        targetValue = 1f + audioLevel * 0.5f,
        animationSpec = tween(durationMillis = 80),
        label = "liveVoiceOrbLevel",
    )
    val echoScale by animateFloatAsState(
        targetValue = 1f + audioLevel * 0.25f,
        animationSpec = tween(durationMillis = 220),
        label = "liveVoiceOrbEcho",
    )
    val halo by rememberInfiniteTransition(label = "liveVoiceOrbHalo").animateFloat(
        initialValue = 0.75f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liveVoiceOrbHaloAlpha",
    )

    val primary = MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
        when {
            state == LiveCallState.ACTIVE && allowMotion -> {
                // Soft breathing halo — the "call is alive" cue.
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .scale(halo * (if (muted) 0.85f else 1f))
                        .alpha((if (muted) 0.10f else 0.22f) * halo)
                        .background(color = primary.copy(alpha = 0.35f), shape = CircleShape),
                )
                // Delayed echo ring.
                Box(
                    modifier = Modifier
                        .size(108.dp)
                        .scale(echoScale)
                        .alpha(0.25f)
                        .background(color = primary.copy(alpha = 0.30f), shape = CircleShape),
                )
                // Core disc with the direct level scale.
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .scale(levelScale)
                        .background(color = primary.copy(alpha = 0.60f), shape = CircleShape),
                )
                // Inner mic icon confirms what the orb represents.
                Icon(
                    imageVector = if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(32.dp),
                )
            }

            state == LiveCallState.ACTIVE && !allowMotion -> {
                // Reduce-motion: static presence disc, color still communicates state.
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(
                            color = primary.copy(alpha = if (muted) 0.18f else 0.45f),
                            shape = CircleShape,
                        ),
                )
                Icon(
                    imageVector = if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(32.dp),
                )
            }

            state == LiveCallState.CONNECTING || state == LiveCallState.RECONNECTING -> {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(color = primary.copy(alpha = 0.14f), shape = CircleShape),
                )
                MotionAwareCircularProgressIndicator(modifier = Modifier.size(40.dp))
            }

            else -> {
                // IDLE / ENDED: quiet static disc.
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

/**
 * Soft radial glow tinted by the active color scheme's primary — gives the call screen depth
 * without deviating from Agora's theming (no hardcoded colors anywhere on this screen).
 */
@Composable
private fun AmbientCallBackdrop(content: @Composable () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            primary.copy(alpha = 0.10f),
                            surface,
                        ),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY,
                    ),
                ),
        )
        content()
    }
}

@Composable
private fun TranscriptLine(side: LiveTranscriptLog.Side, text: String) {
    // The user's own speech renders like the user's chat messages ("Tú", trailing edge,
    // primary container); the model's renders like incoming assistant messages ("Asistente",
    // leading edge, surface variant). Sides come straight from LiveTranscriptLog, where
    // inputTranscription is USER and outputTranscription is MODEL.
    val isUser = side == LiveTranscriptLog.Side.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Text(
                text = stringResource(
                    if (isUser) R.string.live_voice_you else R.string.live_voice_assistant,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (isUser) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.widthIn(max = 420.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}
