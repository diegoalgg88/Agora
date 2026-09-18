package com.newoether.agora.assistant.live

import android.content.Context
import com.newoether.agora.AgoraApplication
import com.newoether.agora.R
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.di.AppContainer
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.selectedVisibleContextMessageIds
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI-facing call state. */
internal enum class LiveCallState { IDLE, CONNECTING, ACTIVE, RECONNECTING, ENDED }

/**
 * Orchestrates one Live voice call: microphone capture → WebSocket → playback, and persists
 * each completed logical turn as an ordinary USER + MODEL message pair on a fresh,
 * immediately-COMPLETED Run in the `"assistant-voice"` conversation (owner decision §10.9.1).
 *
 * This is the explicitly scoped transport exception documented in the plan (§10.4) and
 * `development/system-assistant.md`: Room remains the durable truth; there is no second
 * generation pipeline, queue, or Stop path — the call simply ends and the transcript is already
 * durable. Reconnection via `sessionResumption` is automatic, surfaced as a brief
 * "Reconnecting…" state (owner decision §10.9.4). Audio is never persisted.
 */
internal class LiveVoiceSessionController(
    private val appContext: Context,
    private val onStateChange: (LiveCallState, String?) -> Unit,
    /** Normalized (0f..1f) mic input level, sampled at capture cadence (~32 ms). Drives the
     *  call-screen animation; not persisted or sent anywhere. */
    private val onAudioLevel: (Float) -> Unit = {},
    /** Live transcript of the turn in flight: everything the user has said so far this turn
     *  and everything the model has said so far this turn. Called after each server update;
     *  the turn's accumulated text, not a delta. Drives the call screen's live captions. */
    private val onTranscript: (user: String, model: String) -> Unit = { _, _ -> },
    /** Fired when the in-flight turn is committed (turnComplete) or flushed on teardown —
     *  the live-caption view finalizes its pending lines into the transcript history. */
    private val onTurnCommitted: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var client: GeminiLiveClient? = null
    private var capture: LiveAudioCapture? = null
    private val playback = LiveAudioPlayback()
    private var reconnectJob: Job? = null

    /** Consecutive reconnect attempts without an intervening Ready; reset once Ready arrives. */
    private var reconnectAttempt = 0

    /** Call parameters — immutable for the connection's lifetime (API constraint). */
    @Volatile private var apiKey: String = ""
    @Volatile private var modelId: String = ""
    @Volatile private var voiceName: String = ""

    /** Newest persisted MODEL message / Run — the chain parent of the next turn. */
    @Volatile private var leafMessageId: String? = null
    @Volatile private var leafRunId: String? = null

    /** Transcription accumulators for the turn in flight. */
    private val turnInputText = StringBuilder()
    private val turnOutputText = StringBuilder()
    @Volatile private var muted = false

    val isEchoCancellationAvailable: Boolean
        get() = capture?.echoCancellationAvailable
            ?: android.media.audiofx.AcousticEchoCanceler.isAvailable()

    fun startCall() {
        check(client == null) { "Call already started" }
        publish(LiveCallState.CONNECTING, null)
        scope.launch {
            val container = (appContext.applicationContext as AgoraApplication).awaitContainer()
            if (container == null) {
                publish(LiveCallState.ENDED, appContext.getString(R.string.live_voice_error_app_not_ready))
                return@launch
            }
            val settings = container.settingsRepository
            val key = activeGoogleApiKey(settings)
            if (key.isNullOrBlank()) {
                publish(LiveCallState.ENDED, appContext.getString(R.string.live_voice_error_no_api_key))
                return@launch
            }
            apiKey = key
            modelId = settings.liveVoiceModelId.value.ifBlank { DEFAULT_MODEL_ID }
            voiceName = settings.liveVoiceVoiceName.value.ifBlank {
                LivePrebuiltVoiceConfig.DEFAULT_VOICE_NAME
            }
            withContext(Dispatchers.IO) { prepareConversation(container) }
            connect()
        }
    }

    private fun connect() {
        client = GeminiLiveClient(
            apiKey = apiKey,
            events = ::onConnectionEvent,
            onContent = ::onServerContent,
        ).also { it.connect(modelId, voiceName) }
    }

    private fun onConnectionEvent(event: LiveConnectionEvent) {
        when (event) {
            LiveConnectionEvent.Ready -> {
                reconnectJob?.cancel()
                reconnectJob = null
                reconnectAttempt = 0
                if (capture == null) {
                    playback.start()
                    startCapture()
                }
                publish(LiveCallState.ACTIVE, null)
            }
            is LiveConnectionEvent.ServerGoingAway -> {
                DebugLog.d(TAG, "Server going away in ${event.timeLeftSeconds}s")
                scheduleReconnect(event.timeLeftSeconds * 1000L)
            }
            is LiveConnectionEvent.Disconnected -> {
                if (client == null) return // user hangup raced the failure callback
                DebugLog.w(TAG, "Disconnected (attempt ${reconnectAttempt + 1}): ${event.cause}")
                reconnectAttempt++
                if (reconnectAttempt > MAX_RECONNECT_ATTEMPTS) {
                    DebugLog.w(TAG, "Reconnect budget exhausted")
                    failCall(appContext.getString(R.string.live_voice_error_reconnect_failed))
                    return
                }
                val backoff = (RECONNECT_BACKOFF_MILLIS shl (reconnectAttempt - 1).coerceAtMost(3))
                    .coerceAtMost(MAX_RECONNECT_BACKOFF_MILLIS)
                scheduleReconnect(backoff)
            }
        }
    }

    private fun onServerContent(content: LiveServerContent) {
        val audioParts = content.modelTurn?.parts?.count { it.inlineData?.data?.isNotEmpty() == true } ?: 0
        if (audioParts > 0 || content.interrupted == true || content.turnComplete == true ||
            content.inputTranscription != null || content.outputTranscription != null
        ) {
            DebugLog.d(
                TAG,
                "serverContent: audioParts=$audioParts interrupted=${content.interrupted} " +
                    "turnComplete=${content.turnComplete} " +
                    "inputChars=${content.inputTranscription?.text?.length ?: 0} " +
                    "outputChars=${content.outputTranscription?.text?.length ?: 0}",
            )
        }
        content.modelTurn?.parts?.forEach { part ->
            part.inlineData?.data?.takeIf { it.isNotEmpty() }?.let { base64 ->
                runCatching { Base64.getDecoder().decode(base64) }
                    .getOrNull()
                    ?.let(playback::enqueue)
            }
        }
        if (content.interrupted == true) {
            // Barge-in: drop queued audio and the partial model transcription — the turn that
            // was interrupted never completes, so nothing of it may be persisted.
            playback.flushForInterruption()
            synchronized(turnInputText) { turnOutputText.setLength(0) }
        }
        val (userSoFar, modelSoFar) = synchronized(turnInputText) {
            content.inputTranscription?.text?.let(turnInputText::append)
            content.outputTranscription?.text?.let(turnOutputText::append)
            turnInputText.toString() to turnOutputText.toString()
        }
        onTranscript(userSoFar, modelSoFar)
        if (content.turnComplete == true) persistTurn()
    }

    /** Transparent reconnect with the last server-issued resumption handle (valid ~2 h). */
    private fun scheduleReconnect(delayMillis: Long) {
        if (client == null) return
        reconnectJob?.cancel()
        publish(LiveCallState.RECONNECTING, null)
        reconnectJob = scope.launch {
            delay(delayMillis.coerceAtLeast(RECONNECT_BACKOFF_MILLIS))
            val previous = client ?: return@launch
            val handle = previous.lastResumptionHandle
            previous.close()
            client = previous
            previous.connect(modelId, voiceName, resumptionHandle = handle)
        }
    }

    private fun startCapture() {
        var loggedFirstFrame = false
        capture = LiveAudioCapture(
            onFrame = { frame ->
                if (!muted) {
                    val sent = client?.sendAudio(frame) ?: false
                    if (!loggedFirstFrame) {
                        loggedFirstFrame = true
                        DebugLog.d(TAG, "First mic frame sent: bytes=${frame.size} sent=$sent")
                    }
                }
            },
            onLevel = onAudioLevel,
        ).also { it.start(appContext) }
    }

    /** Mute gates ONLY the user's outgoing mic frames; the model side keeps playing untouched.
     *  No `audioStreamEnd` is sent on mute: that frame commits the pending user turn, and a
     *  committed turn is a client message that interrupts in-flight model generation — the
     *  observed "mute silences the assistant" bug (owner report 2026-09-17). The server VAD
     *  simply sees the stream go quiet and the turn closes naturally on the next speech. */
    fun setMuted(muted: Boolean) {
        this.muted = muted
    }

    fun endCall() {
        stopCallResources()
        publish(LiveCallState.ENDED, null)
    }

    /** Terminal failure after the reconnect budget is exhausted: same teardown, visible cause. */
    private fun failCall(message: String) {
        stopCallResources()
        publish(LiveCallState.ENDED, message)
    }

    private fun stopCallResources() {
        reconnectJob?.cancel()
        reconnectJob = null
        client?.close()
        client = null
        capture?.stop()
        capture = null
        playback.stop()
        flushDanglingTurn()
    }

    fun dispose() {
        endCall()
        scope.cancel()
    }

    /**
     * The socket is gone but a user turn may have started; its spoken text is durable
     * conversation content. Persist it with an empty MODEL reply rather than dropping it
     * ("never lose user data", development/README.md §4.2) — without fabricating a response.
     * Runs on its own scope so it still commits if the UI tears the controller down right away.
     */
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun flushDanglingTurn() {
        val spoken = synchronized(turnInputText) {
            val text = turnInputText.toString().trim()
            turnInputText.setLength(0)
            turnOutputText.setLength(0)
            text
        }
        onTurnCommitted()
        if (spoken.isEmpty()) return
        flushScope.launch { persistTurnBlocking(spoken, replied = "") }
    }

    /**
     * Commits one finished logical turn: fresh Run that begins and ends COMPLETED in a single
     * transaction (live-slot fence respected), USER row from the input transcription, MODEL row
     * from the output transcription, chained onto the previous turn's MODEL row.
     */
    private fun persistTurn() {
        val (spoken, replied) = synchronized(turnInputText) {
            val input = turnInputText.toString().trim()
            val output = turnOutputText.toString().trim()
            turnInputText.setLength(0)
            turnOutputText.setLength(0)
            input to output
        }
        if (spoken.isEmpty() && replied.isEmpty()) return
        onTurnCommitted()
        scope.launch { persistTurnBlocking(spoken, replied) }
    }

    private suspend fun persistTurnBlocking(spoken: String, replied: String) {
        runCatching {
            val container = (appContext.applicationContext as AgoraApplication).awaitContainer()
                ?: return
            withContext(Dispatchers.IO) {
                val conversation = resolveConversation(container)
                val effectiveModelId = modelId.ifBlank { DEFAULT_MODEL_ID }
                val runId = UUID.randomUUID().toString()
                val userMessage = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversation.id,
                    parentId = leafMessageId,
                    text = spoken,
                    status = MessageStatus.SUCCESS,
                    participant = Participant.USER,
                    timestamp = System.currentTimeMillis(),
                    runId = runId,
                    runSequence = 0,
                    consumedAtPass = 0,
                )
                val modelMessage = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversation.id,
                    parentId = userMessage.id,
                    text = replied,
                    status = MessageStatus.SUCCESS,
                    participant = Participant.MODEL,
                    timestamp = userMessage.timestamp + 1,
                    modelName = effectiveModelId,
                    runId = runId,
                    runSequence = 1,
                )
                val selectionUpdates = buildMap<String?, String> {
                    leafMessageId?.let { put(it, userMessage.id) }
                    put(userMessage.id, modelMessage.id)
                }
                container.chatDao.createCompletedRunWithMessages(
                    run = RunEntity(
                        id = runId,
                        conversationId = conversation.id,
                        parentRunId = leafRunId,
                        status = RunStatus.COMPLETED,
                        activeSlot = null,
                        startedAt = userMessage.timestamp,
                        lastCheckpointAt = modelMessage.timestamp,
                        endedAt = modelMessage.timestamp,
                        endReason = RunEndReason.MODEL_COMPLETED,
                    ),
                    messages = listOf(userMessage, modelMessage),
                    messageSelectionUpdates = selectionUpdates,
                    conversationModelId = effectiveModelId,
                    at = modelMessage.timestamp,
                )
                leafMessageId = modelMessage.id
                leafRunId = runId
            }
        }.onFailure { DebugLog.w(TAG, "Live turn persist failed: ${it.message}") }
    }

    /** Resolves (creating when needed) the dedicated `"assistant-voice"` conversation. */
    private suspend fun resolveConversation(container: AppContainer): ChatEntity {
        val repository = container.conversationRepository
        val settings = container.settingsRepository
        val reuse = settings.liveVoiceReuseConversationEnabled.value
        val existing = if (reuse) repository.getConversationByOrigin(ORIGIN) else null
        return existing ?: ChatEntity(
            id = UUID.randomUUID().toString(),
            title = appContext.getString(R.string.live_voice_conversation_title),
            origin = ORIGIN,
        ).also { repository.upsertConversation(it) }
    }

    /** Seeds the turn chain from the conversation's selected leaf before the first turn. */
    private suspend fun prepareConversation(container: AppContainer) {
        val conversation = resolveConversation(container)
        if (leafMessageId != null) return
        container.conversationRepository
            .getProviderContextTopologySnapshot(conversation.id)
            ?.let { snapshot ->
                val leafId = selectedVisibleContextMessageIds(snapshot).lastOrNull()
                snapshot.messages.firstOrNull { it.id == leafId }?.let { leaf ->
                    leafMessageId = leaf.id
                    leafRunId = leaf.runId
                }
            }
    }

    private fun publish(state: LiveCallState, error: String?) {
        onStateChange(state, error)
    }

    private companion object {
        const val TAG = "LiveVoiceSession"
        const val ORIGIN = "assistant-voice"
        const val DEFAULT_MODEL_ID = com.newoether.agora.data.AssistantToolSettings.DEFAULT_LIVE_MODEL_ID
        const val RECONNECT_BACKOFF_MILLIS = 1_000L
        const val MAX_RECONNECT_BACKOFF_MILLIS = 8_000L
        const val MAX_RECONNECT_ATTEMPTS = 5

        fun activeGoogleApiKey(settings: SettingsRepository): String? {
            val activeKey = settings.activeApiKeyIds.value[Constants.PROVIDER_GOOGLE]
            return settings.apiKeys.value.find { it.id == activeKey }?.key
        }
    }
}
