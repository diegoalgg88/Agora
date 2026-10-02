package com.newoether.agora.assistant.live

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Microphone capture for the Live voice call: 16 kHz mono PCM16 in ~32 ms frames, pushed to
 * [onFrame] on a dedicated capture thread. Attaches the platform echo canceller / noise
 * suppressor when the device provides them (`VOICE_COMMUNICATION` source). Audio is streamed
 * straight to the socket and never persisted (plan §10.7).
 */
internal class LiveAudioCapture(
    private val onFrame: (ByteArray) -> Unit,
    /** Normalized (0f..1f) RMS amplitude of each captured frame — drives the call-screen
     *  animation. Cheap enough to compute per frame (1024 samples, ~32 ms cadence). */
    private val onLevel: (Float) -> Unit = {},
    /** The mic entered a dead state (`read` returned an error such as ERROR_DEAD_OBJECT after
     *  the system revoked or re-routed the input). Fired at most once per call; the controller
     *  ends the call visibly instead of leaving a "connected but deaf" session busy-spinning. */
    private val onFailure: () -> Unit = {},
) {
    private var record: AudioRecord? = null
    private var echoCanceler: android.media.audiofx.AcousticEchoCanceler? = null
    private var noiseSuppressor: android.media.audiofx.NoiseSuppressor? = null
    private val running = AtomicBoolean(false)
    private var audioManager: AudioManager? = null
    private var previousMode: Int = -1
    private var previousSpeakerphoneOn: Boolean = false
    private var forcedSpeakerRoute = false
    private var captureThread: Thread? = null

    val echoCancellationAvailable: Boolean
        get() = android.media.audiofx.AcousticEchoCanceler.isAvailable()

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the caller before start().
    fun start(context: android.content.Context) {
        check(!running.get()) { "Capture already started" }
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(minBytes, FRAME_BYTES * 4)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("Microphone unavailable")
        }
        audioManager = context.getSystemService(AudioManager::class.java)
        previousMode = audioManager?.mode ?: -1
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        // MODE_IN_COMMUNICATION + USAGE_VOICE_COMMUNICATION playback (LiveAudioPlayback) follow
        // normal telephony routing, which defaults to the earpiece — force the loudspeaker so a
        // hands-free assistant call doesn't regress into a barely-audible in-ear call. A
        // connected headset wins over the loudspeaker: the user chose where to listen. See
        // forceSpeakerRoute() for the API-31 split (the legacy speakerphone toggle is a no-op
        // for targetSdk S+ — owner report 2026-09-28: call audio came out of the earpiece).
        forceSpeakerRoute()
        if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
            echoCanceler = android.media.audiofx.AcousticEchoCanceler.create(recorder.audioSessionId)
        }
        if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
            noiseSuppressor = android.media.audiofx.NoiseSuppressor.create(recorder.audioSessionId)
        }
        record = recorder
        running.set(true)
        recorder.startRecording()
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ByteArray(FRAME_BYTES)
            while (running.get()) {
                val read = recorder.read(buffer, 0, FRAME_BYTES)
                if (!running.get()) break // stop() raced this read: not a failure
                if (read < 0) {
                    // read() reports errors as negative values (e.g. ERROR_DEAD_OBJECT after a
                    // revoked/re-routed input). Retrying would busy-spin with a dead recorder:
                    // surface the failure and let the controller end the call. `running` must
                    // stay TRUE here: the controller's teardown calls stop(), whose
                    // compareAndSet(true, false) gate is what releases the recorder, effects
                    // and the forced audio mode/route — clearing it first would leak them all.
                    onFailure()
                    break
                }
                if (read == 0) continue
                val chunk = if (read == FRAME_BYTES) buffer.copyOf() else buffer.copyOf(read)
                if (!running.get()) break // stop() raced this frame: drop it
                onLevel(pcm16RmsLevel(chunk))
                onFrame(chunk)
            }
        }.apply { name = "LiveVoiceCapture" }.also { captureThread = it }.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // Order matters: stop() unblocks a pending read, the thread is retired, and only then
        // is the recorder released — never release under a thread still inside read().
        record?.let { recorder -> runCatching { recorder.stop() } }
        // Retire the callback thread before this method returns, so no late onFrame/onLevel
        // fires after stop(). Guarded: the mic-failure path (onFailure → controller failCall →
        // stop()) lands here ON the capture thread, and joining oneself would stall for the
        // full timeout.
        captureThread?.let { thread ->
            if (Thread.currentThread() !== thread) {
                runCatching { thread.join(THREAD_JOIN_TIMEOUT_MILLIS) }
            }
        }
        captureThread = null
        record?.release()
        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        record = null
        audioManager?.let {
            if (previousMode >= 0) it.mode = previousMode
        }
        restoreSpeakerRoute()
        audioManager = null
    }

    /**
     * Routes call audio to the loudspeaker unless a headset is already the active
     * communication device. API 31+: [AudioManager.setCommunicationDevice] — the legacy
     * `isSpeakerphoneOn` toggle is deprecated and a NO-OP for apps targeting S+, which is why
     * the call used to fall back to the earpiece. API 26–30: the legacy toggle, skipped when a
     * Bluetooth SCO route is already up (wired headsets route themselves on MODE_IN_COMMUNICATION).
     */
    private fun forceSpeakerRoute() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val active = manager.communicationDevice
            if (active != null && isHeadsetType(active.type)) return
            val speaker = manager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (speaker != null && manager.setCommunicationDevice(speaker)) {
                forcedSpeakerRoute = true
            }
        } else {
            @Suppress("DEPRECATION")
            if (!manager.isBluetoothScoOn) {
                previousSpeakerphoneOn = manager.isSpeakerphoneOn
                manager.isSpeakerphoneOn = true
                forcedSpeakerRoute = true
            }
        }
    }

    /** Undoes exactly what [forceSpeakerRoute] changed — nothing else the user had configured. */
    private fun restoreSpeakerRoute() {
        val manager = audioManager ?: return
        if (!forcedSpeakerRoute) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = previousSpeakerphoneOn
        }
        forcedSpeakerRoute = false
    }

    private fun isHeadsetType(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> true
        else -> false
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 1024 samples ≈ 32 ms at 16 kHz — inside the ~20–40 ms window (plan §10.7). */
        const val FRAME_SAMPLES = 1024
        const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val THREAD_JOIN_TIMEOUT_MILLIS = 300L

        /** RMS of a PCM16LE frame, normalized against a loud-speech reference amplitude and
         *  clamped to 0f..1f. Not calibrated audio metering — just enough signal for a call-
         *  screen animation to visibly react to voice. */
        internal fun pcm16RmsLevel(pcm16: ByteArray): Float {
            if (pcm16.size < 2) return 0f
            var sumSquares = 0.0
            var sampleCount = 0
            var i = 0
            while (i + 1 < pcm16.size) {
                val sample = ((pcm16[i + 1].toInt() shl 8) or (pcm16[i].toInt() and 0xFF)).toShort()
                sumSquares += sample * sample.toDouble()
                sampleCount++
                i += 2
            }
            if (sampleCount == 0) return 0f
            val rms = kotlin.math.sqrt(sumSquares / sampleCount)
            return (rms / REFERENCE_LOUD_AMPLITUDE).toFloat().coerceIn(0f, 1f)
        }

        /** ~10% of Short.MAX_VALUE; normal conversational speech peaks well above this. */
        private const val REFERENCE_LOUD_AMPLITUDE = 3_000.0
    }
}
