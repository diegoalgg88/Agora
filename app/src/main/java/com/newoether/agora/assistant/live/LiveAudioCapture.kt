package com.newoether.agora.assistant.live

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
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
) {
    private var record: AudioRecord? = null
    private var echoCanceler: android.media.audiofx.AcousticEchoCanceler? = null
    private var noiseSuppressor: android.media.audiofx.NoiseSuppressor? = null
    private val running = AtomicBoolean(false)
    private var audioManager: AudioManager? = null
    private var previousMode: Int = -1

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
                if (read <= 0) continue
                val chunk = if (read == FRAME_BYTES) buffer.copyOf() else buffer.copyOf(read)
                onLevel(pcm16RmsLevel(chunk))
                onFrame(chunk)
            }
        }.apply { name = "LiveVoiceCapture" }.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        record?.let { recorder ->
            runCatching { recorder.stop() }
            recorder.release()
        }
        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        record = null
        audioManager?.takeIf { previousMode >= 0 }?.mode = previousMode
        audioManager = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 1024 samples ≈ 32 ms at 16 kHz — inside the ~20–40 ms window (plan §10.7). */
        const val FRAME_SAMPLES = 1024
        const val FRAME_BYTES = FRAME_SAMPLES * 2

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
