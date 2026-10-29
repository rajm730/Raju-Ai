package com.raju.aiassistant.data.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.raju.aiassistant.domain.assistant.VoiceError
import com.raju.aiassistant.domain.assistant.VoiceInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Speech-to-text with Android's SpeechRecognizer. Runs only while the user has started it
 * (visible listening indicator); never records in the background. Must be used on the main thread.
 */
class VoiceInputManager(private val context: Context) : VoiceInput {
    private var recognizer: SpeechRecognizer? = null
    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private val _partial = MutableStateFlow("")
    override val partial: StateFlow<String> = _partial.asStateFlow()

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun start(languageTag: String, onResult: (String) -> Unit, onError: (VoiceError) -> Unit) {
        if (!hasPermission()) {
            onError(VoiceError("Microphone permission is needed for voice. Allow it when asked, or in Settings → Apps → Seema AI → Permissions.", permissionDenied = true))
            return
        }
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        _partial.value = ""
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _listening.value = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _listening.value = false }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { _partial.value = it }
            }

            override fun onResults(results: Bundle?) {
                _listening.value = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                release()
                if (text.isBlank()) onError(VoiceError("I didn't catch that.", noSpeech = true)) else onResult(text)
            }

            override fun onError(error: Int) {
                _listening.value = false
                release()
                onError(mapError(error))
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        _listening.value = true
        r.startListening(intent)
    }

    override fun stop() {
        recognizer?.let { runCatching { it.cancel() } }
        release()
        _listening.value = false
    }

    private fun release() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
    }

    private fun mapError(code: Int): VoiceError = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceError("I didn't hear anything.", noSpeech = true)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceError("Microphone permission denied.", permissionDenied = true)
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceError("Speech recognition needs internet (network error).")
        SpeechRecognizer.ERROR_AUDIO -> VoiceError("Microphone audio error. Another app may be using the mic.")
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceError("Speech recognizer is busy. Try again.")
        SpeechRecognizer.ERROR_SERVER -> VoiceError("Speech recognition server error.")
        else -> VoiceError("Speech recognition error ($code).")
    }
}
