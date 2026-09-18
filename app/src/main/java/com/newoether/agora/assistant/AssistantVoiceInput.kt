package com.newoether.agora.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.newoether.agora.util.DebugLog

/**
 * Thin wrapper around [SpeechRecognizer] for the assistant overlay. Prefers the on-device
 * recognizer (API 31+, guarded per AGENTS.md §3.2.9) and falls back to the network one.
 * Results are partial-inlined into the prompt field and finalized on [onFinal]; no audio is
 * ever persisted. See `development/system-assistant.md`.
 */
class AssistantVoiceInput(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: () -> Unit,
) : RecognitionListener {

    private var recognizer: SpeechRecognizer? = null

    /** True while the current attempt uses the on-device engine; lets [onError] fall back to
     *  the network recognizer exactly once instead of giving up, since some OEMs report
     *  [SpeechRecognizer.isOnDeviceRecognitionAvailable] as true without an installed language
     *  pack, which fails instantly on [start]. */
    private var usingOnDevice = false
    private var firedAnyResult = false
    private var fellBackToNetwork = false

    val isAvailable: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ||
                SpeechRecognizer.isRecognitionAvailable(context)
        } else {
            SpeechRecognizer.isRecognitionAvailable(context)
        }

    fun start() {
        if (recognizer != null) return
        firedAnyResult = false
        fellBackToNetwork = false
        startInternal(preferOnDevice = true)
    }

    private fun startInternal(preferOnDevice: Boolean) {
        usingOnDevice = preferOnDevice &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val engine = if (usingOnDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        engine.setRecognitionListener(this)
        engine.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
        )
        recognizer = engine
    }

    fun stop() {
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit

    override fun onPartialResults(partialResults: Bundle?) {
        partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                firedAnyResult = true
                onPartial(it)
            }
    }

    override fun onResults(results: Bundle?) {
        results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                firedAnyResult = true
                onFinal(it)
            }
            ?: onError()
    }

    override fun onError(error: Int) {
        DebugLog.w(TAG, "Speech recognition error $error (onDevice=$usingOnDevice)")
        // Some OEMs report isOnDeviceRecognitionAvailable() = true without an installed language
        // pack; the on-device engine then fails immediately with no partial/final result ever
        // fired. Retry once with the network recognizer instead of surfacing the error to the UI.
        if (usingOnDevice && !firedAnyResult && !fellBackToNetwork) {
            fellBackToNetwork = true
            recognizer?.destroy()
            recognizer = null
            startInternal(preferOnDevice = false)
            return
        }
        onError()
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private companion object {
        const val TAG = "AssistantVoiceInput"
    }
}
