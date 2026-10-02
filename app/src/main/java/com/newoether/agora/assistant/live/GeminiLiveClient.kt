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
import java.util.concurrent.atomic.AtomicInteger
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
 * handle is driven by [LiveVoiceSessionController], which calls [close] and then [connect] again
 * with the last issued handle on the SAME instance. The BYOK API key travels as the `key` query
 * parameter — same trust model as `GeminiProvider`'s SSE calls (plan §10.3); never log the URL.
 * No audio is ever persisted by this client.
 *
 * Socket identity: every [connect] starts a new generation and every [close] ends it. All
 * callbacks of a socket (including its watchdog) check that their generation is still current
 * and are dropped otherwise, so a late `onClosed`/`onFailure`/frame from a socket that was
 * closed for a reconnect can never be mistaken for the health of the new connection.
 */
internal class GeminiLiveClient(
    private val apiKey: String,
    private val events: (LiveConnectionEvent) -> Unit,
    /** Turn audio, transcriptions, interruptions and turn-completion markers. */
    private val onContent: (LiveServerContent) -> Unit,
    private val json: Json = LiveJson,
    /** Socket factory; overridable so frame dispatch and lifecycle can be unit-tested. */
    private val webSocketFactory: WebSocket.Factory = defaultWebSocketFactory(),
) {
    @Volatile private var socket: WebSocket? = null

    /** Incremented by [connect] and [close]; a callback is live only while its captured value
     *  still equals this counter. */
    private val generation = AtomicInteger(0)

    /** Cancels the per-connection setup-ack watchdog once `setupComplete` arrives. */
    @Volatile private var setupWatchdog: ScheduledFuture<*>? = null

    /** Last resumption handle announced by the server; blank until the first update arrives. */
    @Volatile var lastResumptionHandle: String = ""
        private set
    @Volatile private var lastResumable: Boolean = false

    /**
     * Opens a connection and sends the mandatory setup frame. [resumptionHandle] is blank for a
     * fresh session, or a previously issued handle after a drop/`goAway`.
     *
     * Contract: requires that no socket is open — after a previous [connect] the caller must
     * call [close] first ([close] nulls the socket and invalidates all of its pending callbacks).
     */
    fun connect(
        modelId: String,
        voiceName: String,
        resumptionHandle: String = "",
        sensitivity: VoiceSensitivity = VoiceSensitivity.DEFAULT,
    ) {
        check(socket == null) { "Live client already connected; close() before connecting again" }
        check(apiKey.isNotBlank()) { "No Gemini API key configured" }
        val gen = generation.incrementAndGet()
        fun isCurrent() = gen == generation.get()
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
            realtimeInputConfig = LiveRealtimeInputConfig(
                automaticActivityDetection = LiveAutomaticActivityDetection(
                    startOfSpeechSensitivity = sensitivity.startOfSpeechSensitivity,
                    endOfSpeechSensitivity = sensitivity.endOfSpeechSensitivity,
                    prefixPaddingMs = sensitivity.prefixPaddingMs,
                    silenceDurationMs = sensitivity.silenceDurationMs,
                ),
            ),
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
        socket = webSocketFactory.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent()) return
                val setupJson = json.encodeToString(LiveClientFrame(setup = setup))
                DebugLog.d(TAG, "Socket opened (code=${response.code}); sending setup: $setupJson")
                val sent = webSocket.send(setupJson)
                if (!sent) reportDisconnect(false, "setup send rejected")
                // Watchdog: a socket that opens but never delivers setupComplete would
                // otherwise leave the caller in limbo until the ping interval kills it
                // (or forever, if the server closes silently). Fail fast instead.
                setupWatchdog = watchdogScheduler.schedule(
                    {
                        if (isCurrent()) {
                            DebugLog.w(
                                TAG,
                                "Setup ack timeout: no setupComplete within " +
                                    "$SETUP_ACK_TIMEOUT_SECONDS s",
                            )
                            webSocket.cancel()
                            reportDisconnect(lastResumable, "setup ack timeout")
                        }
                    },
                    SETUP_ACK_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isCurrent()) dispatch(text)
            }

            // The Live API sends every server frame (setupComplete, serverContent, goAway,
            // sessionResumptionUpdate) as a BINARY WebSocket frame carrying UTF-8 JSON text,
            // not a TEXT frame — confirmed against the raw wire bytes (opcode 0x2). Without
            // this override every server response was silently dropped by OkHttp's default
            // no-op, which looked identical to the server never responding at all.
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                if (isCurrent()) dispatch(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent()) return
                // Never interpolate t.message: OkHttp failure messages can embed the request
                // URL, which carries the BYOK key. The tr-variant logs type + app frames only.
                DebugLog.w(TAG, "Live socket failure", t)
                setupWatchdog?.cancel(false)
                setupWatchdog = null
                reportDisconnect(
                    resumable = lastResumable,
                    // Type only: the cause string is logged by the controller, and t.message
                    // may embed the key-bearing request URL.
                    cause = "failure: ${t.javaClass.simpleName}",
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent()) return
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
        // Never log the raw frame: serverContent carries the user's and the model's transcription.
        val frame = runCatching { json.decodeFromString<LiveServerFrame>(text) }
            .getOrElse { error ->
                // Decoding errors can quote the offending input — length only, plus the safe
                // throwable summary.
                DebugLog.w(TAG, "Unparseable live server frame: chars=${text.length}", error)
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
            else -> DebugLog.d(TAG, "Ignored live server frame (no handled field set): chars=${text.length}")
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

    /** Ends the current generation (dropping every pending callback of the socket), cancels the
     *  watchdog and closes the socket with code 1000. Safe to call repeatedly. */
    fun close() {
        generation.incrementAndGet()
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

        fun defaultWebSocketFactory(): WebSocket.Factory = HttpClient.client.newBuilder()
            .pingInterval(java.time.Duration.ofSeconds(PING_INTERVAL_SECONDS))
            .build()

        /** Single shared daemon thread: one outstanding watchdog per connection at most. */
        val watchdogScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "LiveSetupWatchdog").apply { isDaemon = true }
        }

        /** Protobuf Duration strings arrive like "30s" or "1.5s"; default to 0 when opaque. */
        fun parseDurationSeconds(raw: String): Long =
            raw.trimEnd('s').toDoubleOrNull()?.toLong() ?: 0L
    }
}
