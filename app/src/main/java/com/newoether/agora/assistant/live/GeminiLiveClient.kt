package com.newoether.agora.assistant.live

import com.newoether.agora.api.HttpClient
import com.newoether.agora.util.DebugLog
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Connection lifecycle events surfaced to [LiveVoiceSessionController]. */
internal sealed interface LiveConnectionEvent {
    /** Setup accepted by the server; audio can flow. */
    data object Ready : LiveConnectionEvent
    /** Server announced an imminent disconnect — reconnect with the resumption handle. */
    data class ServerGoingAway(val timeLeftSeconds: Long) : LiveConnectionEvent
    /** Socket failed / dropped. [resumable] mirrors the last resumption update. */
    data class Disconnected(val resumable: Boolean, val cause: String) : LiveConnectionEvent
}

/**
 * OkHttp WebSocket client for the Gemini Live API (BidiGenerateContent).
 *
 * Owns exactly one physical connection at a time; reconnection with a session-resumption
 * handle is driven by [LiveVoiceSessionController], which calls [connect] again with the last
 * issued handle. The BYOK API key travels as the `key` query parameter — same trust model as
 * `GeminiProvider`'s SSE calls (plan §10.3). No audio is ever persisted by this client.
 */
internal class GeminiLiveClient(
    private val apiKey: String,
    private val events: (LiveConnectionEvent) -> Unit,
    /** Turn audio, transcriptions, interruptions and turn-completion markers. */
    private val onContent: (LiveServerContent) -> Unit,
    private val json: Json = LiveJson,
) {
    private var socket: WebSocket? = null
    private val closedByUser = AtomicBoolean(false)

    /** Cancels the per-connection setup-ack watchdog once `setupComplete` arrives. */
    @Volatile private var setupWatchdog: ScheduledFuture<*>? = null

    /** Last resumption handle announced by the server; blank until the first update arrives. */
    @Volatile var lastResumptionHandle: String = ""
        private set
    @Volatile private var lastResumable: Boolean = false

    /**
     * Opens a connection and sends the mandatory setup frame. [resumptionHandle] is blank for a
     * fresh session, or a previously issued handle after a drop/`goAway`.
     */
    fun connect(
        modelId: String,
        voiceName: String,
        resumptionHandle: String = "",
    ) {
        check(socket == null) { "Live client already connected" }
        check(apiKey.isNotBlank()) { "No Gemini API key configured" }
        closedByUser.set(false)
        val normalizedHandle = resumptionHandle.takeIf { it.isNotBlank() }
        if (normalizedHandle != null) lastResumptionHandle = normalizedHandle

        val setup = LiveSetup(
            model = normalizeLiveModelId(modelId),
            generationConfig = LiveGenerationConfig(
                responseModalities = listOf("AUDIO"),
                speechConfig = LiveSpeechConfig(
                    voiceConfig = LiveVoiceConfig(
                        prebuiltVoiceConfig = LivePrebuiltVoiceConfig(voiceName = voiceName),
                    ),
                ),
            ),
            inputAudioTranscription = LiveAudioTranscriptionConfig(),
            outputAudioTranscription = LiveAudioTranscriptionConfig(),
            sessionResumption = LiveSessionResumptionConfig(handle = normalizedHandle),
            contextWindowCompression = LiveContextWindowCompression(),
        )
        val request = Request.Builder()
            .url("$WS_ENDPOINT?key=$apiKey")
            .build()
        DebugLog.d(
            TAG,
            "Connecting (${if (normalizedHandle != null) "resuming handle" else "fresh session"})",
        )
        // Exactly one Disconnected event per physical socket, no matter which callback path
        // fires (failure, server close, setup watchdog) or in what order.
        val reported = AtomicBoolean(false)
        fun reportDisconnect(resumable: Boolean, cause: String) {
            if (reported.getAndSet(true)) return
            events(LiveConnectionEvent.Disconnected(resumable = resumable, cause = cause))
        }
        socket = HttpClient.client.newBuilder()
            .pingInterval(java.time.Duration.ofSeconds(PING_INTERVAL_SECONDS))
            .build()
            .newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val setupJson = json.encodeToString(LiveClientFrame(setup = setup))
                    DebugLog.d(TAG, "Socket opened (code=${response.code}); sending setup: $setupJson")
                    val sent = webSocket.send(setupJson)
                    if (!sent) reportDisconnect(false, "setup send rejected")
                    // Watchdog: a socket that opens but never delivers setupComplete would
                    // otherwise leave the caller in limbo until the ping interval kills it
                    // (or forever, if the server closes silently). Fail fast instead.
                    setupWatchdog = watchdogScheduler.schedule(
                        {
                            DebugLog.w(
                                TAG,
                                "Setup ack timeout: no setupComplete within " +
                                    "$SETUP_ACK_TIMEOUT_SECONDS s",
                            )
                            webSocket.cancel()
                            reportDisconnect(lastResumable, "setup ack timeout")
                        },
                        SETUP_ACK_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS,
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    dispatch(text)
                }

                // The Live API sends every server frame (setupComplete, serverContent, goAway,
                // sessionResumptionUpdate) as a BINARY WebSocket frame carrying UTF-8 JSON text,
                // not a TEXT frame — confirmed against the raw wire bytes (opcode 0x2). Without
                // this override every server response was silently dropped by OkHttp's default
                // no-op, which looked identical to the server never responding at all.
                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    dispatch(bytes.utf8())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (closedByUser.get()) return
                    DebugLog.w(TAG, "Live socket failure: ${t.message}")
                    setupWatchdog?.cancel(false)
                    setupWatchdog = null
                    reportDisconnect(
                        resumable = lastResumable,
                        cause = t.message ?: "connection failure",
                    )
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (closedByUser.get()) return
                    DebugLog.w(TAG, "Live socket closed by server: code=$code reason=$reason")
                    setupWatchdog?.cancel(false)
                    setupWatchdog = null
                    reportDisconnect(
                        resumable = lastResumable,
                        cause = "closed code=$code",
                    )
                }
            })
    }

    private fun dispatch(text: String) {
        val frame = runCatching { json.decodeFromString<LiveServerFrame>(text) }
            .getOrElse { error ->
                DebugLog.w(TAG, "Unparseable live server frame: ${error.message} raw=$text")
                return
            }
        when {
            frame.setupComplete != null -> {
                setupWatchdog?.cancel(false)
                setupWatchdog = null
                events(LiveConnectionEvent.Ready)
            }
            frame.sessionResumptionUpdate != null -> {
                lastResumptionHandle = frame.sessionResumptionUpdate.newHandle
                lastResumable = frame.sessionResumptionUpdate.resumable
            }
            frame.goAway != null ->
                events(LiveConnectionEvent.ServerGoingAway(parseDurationSeconds(frame.goAway.timeLeft)))
            frame.serverContent != null -> onContent(frame.serverContent)
            else -> DebugLog.w(TAG, "Unhandled live server frame (no known field set): raw=$text")
        }
    }

    /** Streams a 16 kHz PCM16 frame; returns false when the socket rejected it. */
    fun sendAudio(pcm16: ByteArray): Boolean = send(
        LiveClientFrame(
            realtimeInput = LiveRealtimeInput(
                audio = LiveRealtimeAudio(data = java.util.Base64.getEncoder().encodeToString(pcm16)),
            ),
        ),
    )

    /** Flushes the input audio stream so server VAD can close the user turn. */
    fun sendAudioStreamEnd(): Boolean = send(
        LiveClientFrame(realtimeInput = LiveRealtimeInput(audioStreamEnd = true)),
    )

    private fun send(frame: LiveClientFrame): Boolean =
        socket?.send(json.encodeToString(frame)) ?: false

    fun close() {
        closedByUser.set(true)
        setupWatchdog?.cancel(false)
        setupWatchdog = null
        socket?.close(NORMAL_CLOSE_CODE, "user hangup")
        socket = null
    }

    private companion object {
        const val TAG = "GeminiLiveClient"
        const val WS_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val PING_INTERVAL_SECONDS = 20L
        const val SETUP_ACK_TIMEOUT_SECONDS = 10L
        const val NORMAL_CLOSE_CODE = 1000

        /** Single shared daemon thread: one outstanding watchdog per connection at most. */
        val watchdogScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "LiveSetupWatchdog").apply { isDaemon = true }
        }

        /** Protobuf Duration strings arrive like "30s" or "1.5s"; default to 0 when opaque. */
        fun parseDurationSeconds(raw: String): Long =
            raw.trimEnd('s').toDoubleOrNull()?.toLong() ?: 0L
    }
}
