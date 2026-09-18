package com.newoether.agora.assistant.live

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * Streaming playback for model audio: the Live API always emits 24 kHz mono PCM16. Chunks are
 * written in arrival order on the caller's thread; [flushForInterruption] implements real
 * barge-in — it stops and restarts the track so already-queued frames are discarded
 * immediately, not merely skipped (plan §10.7).
 */
internal class LiveAudioPlayback {
    private var track: AudioTrack? = null
    private val lock = Any()

    fun start() {
        synchronized(lock) {
            check(track == null) { "Playback already started" }
            val minBytes = AudioTrack.getMinBufferSize(
                LiveAudioPlayback.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBytes, DEFAULT_BUFFER_BYTES))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.play() }
        }
    }

    fun enqueue(pcm16: ByteArray) {
        synchronized(lock) {
            track?.write(pcm16, 0, pcm16.size)
        }
    }

    /** Barge-in: drop everything queued and currently playing, then keep the track open. */
    fun flushForInterruption() {
        synchronized(lock) {
            track?.let {
                runCatching {
                    it.pause()
                    it.flush()
                    it.play()
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            track?.let {
                runCatching {
                    it.stop()
                    it.release()
                }
            }
            track = null
        }
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        private const val DEFAULT_BUFFER_BYTES = 8192
    }
}
