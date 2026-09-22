package com.newoether.agora.assistant.live

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * DTOs for the Gemini Live API (BidiGenerateContent) WebSocket protocol.
 *
 * Every client frame carries exactly one of: `setup`, `clientContent`, `realtimeInput`,
 * `toolResponse`. Server frames carry exactly one of: `setupComplete`, `serverContent`,
 * `toolCall`, `goAway`, `sessionResumptionUpdate` (+ optional `usageMetadata`).
 * Reference: https://ai.google.dev/api/live — see `docs/PLAN-20260915-SYSTEM-ASSISTANT.md` §10.
 *
 * v1 scope (owner decision §10.9.3): conversation only — `toolResponse` and function-call
 * fields are modeled for forward compatibility (Fase 4.1) but never produced.
 */
internal val LiveJson: Json = Json {
    ignoreUnknownKeys = true
    // The protocol requires protocol-default fields on the wire (responseModalities,
    // sessionResumption, transcriptions, mimeType), so defaults must be encoded.
    encodeDefaults = true
    explicitNulls = false
}

// ── Client frames ──────────────────────────────────────────────────────────

/** RFC 3339-ish protobuf Duration arrives as a string like "30s"; keep it opaque. */
@Serializable
internal data class LiveClientFrame(
    val setup: LiveSetup? = null,
    @SerialName("realtimeInput") val realtimeInput: LiveRealtimeInput? = null,
)

@Serializable
internal data class LiveSetup(
    val model: String,
    @SerialName("generationConfig") val generationConfig: LiveGenerationConfig = LiveGenerationConfig(),
    /** Ask the server to emit input/output audio transcriptions — our turn-persistence source. */
    @SerialName("inputAudioTranscription") val inputAudioTranscription: LiveAudioTranscriptionConfig =
        LiveAudioTranscriptionConfig(),
    @SerialName("outputAudioTranscription") val outputAudioTranscription: LiveAudioTranscriptionConfig =
        LiveAudioTranscriptionConfig(),
    /** Enable transparent reconnect (handle kept by the controller, valid ~2 h). */
    @SerialName("sessionResumption") val sessionResumption: LiveSessionResumptionConfig =
        LiveSessionResumptionConfig(),
    /** Avoid the 15-minute audio-session cap (owner decision §10.9.4). */
    @SerialName("contextWindowCompression") val contextWindowCompression: LiveContextWindowCompression =
        LiveContextWindowCompression(),
    /** Tunes server-side VAD end-of-turn latency (default Google settings are conservative;
     *  see [LiveAutomaticActivityDetection] for the actual values Agora sends). */
    @SerialName("realtimeInputConfig") val realtimeInputConfig: LiveRealtimeInputConfig =
        LiveRealtimeInputConfig(),
)

@Serializable
internal data class LiveRealtimeInputConfig(
    @SerialName("automaticActivityDetection") val automaticActivityDetection: LiveAutomaticActivityDetection =
        LiveAutomaticActivityDetection(),
)

/**
 * Server-side VAD tuning (https://ai.google.dev/api/live#automaticactivitydetection). Field
 * defaults here mirror [VoiceSensitivity.BALANCED] as a safety net for any caller that
 * constructs this without going through the Settings-driven sensitivity preset; the actual
 * values Agora sends always come from the user's chosen [VoiceSensitivity] (see
 * `GeminiLiveClient.connect`).
 */
@Serializable
internal data class LiveAutomaticActivityDetection(
    @SerialName("startOfSpeechSensitivity") val startOfSpeechSensitivity: String = "START_SENSITIVITY_LOW",
    @SerialName("endOfSpeechSensitivity") val endOfSpeechSensitivity: String = "END_SENSITIVITY_LOW",
    @SerialName("prefixPaddingMs") val prefixPaddingMs: Int = 50,
    @SerialName("silenceDurationMs") val silenceDurationMs: Int = 700,
)

/** Present-but-empty marker object, per the reference. `unused` must never serialize:
 *  [LiveJson.encodeDefaults] would leak it on the wire, so it is a hidden JsonElement that
 *  [LiveJson] suppresses as null. */
@Serializable
internal data class LiveAudioTranscriptionConfig(val unused: JsonElement? = null)

@Serializable
internal data class LiveSessionResumptionConfig(
    /** Null on first connect (field omitted); a previously issued handle on reconnect. */
    val handle: String? = null,
)

@Serializable
internal data class LiveContextWindowCompression(
    @SerialName("slidingWindow") val slidingWindow: LiveSlidingWindow = LiveSlidingWindow(),
)

/** Sliding-window compression marker: `{"slidingWindow":{}}`. */
@Serializable
internal data class LiveSlidingWindow(val unused: JsonElement? = null)

@Serializable
internal data class LiveSetupComplete(val unused: JsonElement? = null)

@Serializable
internal data class LiveGenerationConfig(
    @SerialName("responseModalities") val responseModalities: List<String> = listOf("AUDIO"),
    @SerialName("speechConfig") val speechConfig: LiveSpeechConfig = LiveSpeechConfig(),
)

@Serializable
internal data class LiveSpeechConfig(
    @SerialName("voiceConfig") val voiceConfig: LiveVoiceConfig = LiveVoiceConfig(),
)

@Serializable
internal data class LiveVoiceConfig(
    @SerialName("prebuiltVoiceConfig") val prebuiltVoiceConfig: LivePrebuiltVoiceConfig =
        LivePrebuiltVoiceConfig(),
)

@Serializable
internal data class LivePrebuiltVoiceConfig(
    /** e.g. "Kore", "Puck", "Fenrir". */
    @SerialName("voiceName") val voiceName: String = DEFAULT_VOICE_NAME,
) {
    companion object {
        const val DEFAULT_VOICE_NAME = "Kore"
    }
}

/** Realtime microphone input. `audio` frames stream; `audioStreamEnd` flushes the stream. */
@Serializable
internal data class LiveRealtimeInput(
    val audio: LiveRealtimeAudio? = null,
    @SerialName("audioStreamEnd") val audioStreamEnd: Boolean? = null,
)

@Serializable
internal data class LiveRealtimeAudio(
    /** Fixed: "audio/pcm;rate=16000" (PCM16 little-endian, 16 kHz, mono). */
    val mimeType: String = INPUT_AUDIO_MIME,
    /** Base64 PCM16 frame bytes. */
    val data: String,
) {
    companion object {
        const val INPUT_AUDIO_MIME = "audio/pcm;rate=16000"
    }
}

// ── Server frames ──────────────────────────────────────────────────────────

@Serializable
internal data class LiveServerFrame(
    @SerialName("setupComplete") val setupComplete: LiveSetupComplete? = null,
    @SerialName("serverContent") val serverContent: LiveServerContent? = null,
    @SerialName("goAway") val goAway: LiveGoAway? = null,
    @SerialName("sessionResumptionUpdate") val sessionResumptionUpdate: LiveSessionResumptionUpdate? = null,
)

@Serializable
internal data class LiveServerContent(
    /** Streaming model audio chunks for this turn. */
    @SerialName("modelTurn") val modelTurn: LiveModelTurn? = null,
    /** Barge-in: the user started speaking — drop all queued playback immediately. */
    val interrupted: Boolean? = null,
    /** The model finished this logical turn — persist it (USER + MODEL pair). */
    @SerialName("turnComplete") val turnComplete: Boolean? = null,
    /** Server-side transcription of what the user said in this turn. */
    @SerialName("inputTranscription") val inputTranscription: LiveTranscription? = null,
    /** Server-side transcription of what the model said in this turn. */
    @SerialName("outputTranscription") val outputTranscription: LiveTranscription? = null,
)

@Serializable
internal data class LiveModelTurn(
    val parts: List<LivePart> = emptyList(),
)

@Serializable
internal data class LivePart(
    @SerialName("inlineData") val inlineData: LiveInlineData? = null,
    val text: String? = null,
)

@Serializable
internal data class LiveInlineData(
    val mimeType: String = OUTPUT_AUDIO_MIME,
    /** Base64 PCM16 (24 kHz mono little-endian on output). */
    val data: String = "",
) {
    companion object {
        const val OUTPUT_AUDIO_MIME = "audio/pcm;rate=24000"
    }
}

@Serializable
internal data class LiveTranscription(val text: String = "")

@Serializable
internal data class LiveGoAway(
    /** Protobuf Duration string, e.g. "30s". Opaque; only used as a reconnect signal. */
    @SerialName("timeLeft") val timeLeft: String = "",
)

@Serializable
internal data class LiveSessionResumptionUpdate(
    /** Empty when [resumable] is false (e.g. mid-function-call). */
    @SerialName("newHandle") val newHandle: String = "",
    val resumable: Boolean = false,
)

/** Normalizes a user-facing model id to the `models/<id>` form the setup frame requires. */
internal fun normalizeLiveModelId(modelId: String): String =
    modelId.trim().removePrefix("models/").takeIf { it.isNotEmpty() }?.let { "models/$it" } ?: modelId.trim()

/**
 * User-facing voice-sensitivity presets, mapped to the [LiveAutomaticActivityDetection] wire
 * values. Exposed as a Settings choice (not a hardcoded constant) because the original Fase 4
 * default (RESPONSIVE-equivalent: HIGH end sensitivity, 500 ms silence) cut users off mid-
 * sentence for normal speaking pace/pauses (owner report, 2026-09-17) — no single fixed value
 * suits every speaker, room, or language.
 *
 * `startOfSpeechSensitivity` is pinned to `START_SENSITIVITY_LOW` in **every** preset: on-device
 * testing (owner report, 2026-09-19, `gemini-2.5-flash-native-audio-preview-09-2025`) showed
 * `START_SENSITIVITY_HIGH` reproducibly makes the server’s VAD never fire at all — not “cuts in
 * eagerly” as the name and Google's own docs suggest, but a complete failure to detect any
 * speech for the whole call (four separate attempts, zero `serverContent` ever received). `LOW`
 * worked immediately and consistently. This looks like a real platform/preview-model quirk
 * rather than something fixable by interpreting the field differently on our end — revisit if a
 * future model revision documents different behavior. Only `endOfSpeechSensitivity` /
 * `silenceDurationMs` / `prefixPaddingMs` differ across presets now, which also matches the
 * original complaint (mid-sentence cutoff is an end-of-turn concern, not a start-of-turn one).
 */
internal enum class VoiceSensitivity(
    val startOfSpeechSensitivity: String,
    val endOfSpeechSensitivity: String,
    val prefixPaddingMs: Int,
    val silenceDurationMs: Int,
) {
    /** Waits the longest before ending a turn; best for slower speakers, noisier rooms, or
     *  anyone who pauses mid-sentence to think. */
    PATIENT(
        startOfSpeechSensitivity = "START_SENSITIVITY_LOW",
        endOfSpeechSensitivity = "END_SENSITIVITY_LOW",
        prefixPaddingMs = 100,
        silenceDurationMs = 900,
    ),

    /** Default: end-of-turn latency tuned down from Google's unconfigured baseline without
     *  cutting off an ordinary mid-sentence pause. */
    BALANCED(
        startOfSpeechSensitivity = "START_SENSITIVITY_LOW",
        endOfSpeechSensitivity = "END_SENSITIVITY_LOW",
        prefixPaddingMs = 50,
        silenceDurationMs = 700,
    ),

    /** Fastest turn-taking — the original Fase 4 v1 default (2026-09-17). Can cut off slower
     *  speech or brief thinking pauses; opt-in for people who want the snappiest back-and-forth. */
    RESPONSIVE(
        startOfSpeechSensitivity = "START_SENSITIVITY_LOW",
        endOfSpeechSensitivity = "END_SENSITIVITY_HIGH",
        prefixPaddingMs = 20,
        silenceDurationMs = 500,
    ),
    ;

    companion object {
        val DEFAULT = BALANCED

        /** Falls back to [DEFAULT] for a blank/unknown/legacy stored value. */
        fun fromStorageValue(value: String?): VoiceSensitivity =
            entries.find { it.name == value } ?: DEFAULT
    }
}
