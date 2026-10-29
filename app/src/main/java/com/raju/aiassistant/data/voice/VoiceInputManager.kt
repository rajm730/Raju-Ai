package com.raju.aiassistant.data.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 *
 * Every session gets an id; callbacks from an older session (a recogniser that was replaced or
 * stopped) are ignored, and each session reports exactly one outcome (result or error) — also when
 * the recogniser hangs, thanks to a watchdog.
 */
class VoiceInputManager(private val context: Context, private val log: (String) -> Unit = {}) : VoiceInput {
    private var recognizer: SpeechRecognizer? = null
    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private val _partial = MutableStateFlow("")
    override val partial: StateFlow<String> = _partial.asStateFlow()

    private var session = 0
    private val handler = Handler(Looper.getMainLooper())
    private var watchdog: Runnable? = null

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun start(languageTag: String, onResult: (String) -> Unit, onError: (VoiceError) -> Unit) {
        if (!hasPermission()) {
            onError(VoiceError("Microphone permission is needed for voice. Allow it when asked, or in Settings → Apps → Seema AI → Permissions.", permissionDenied = true))
            return
        }
        stop()
        val id = ++session
        var finished = false
        // Exactly one outcome per session; anything after that (or from an old session) is dropped.
        fun finish(block: () -> Unit) {
            if (finished || id != session) return
            finished = true
            cancelWatchdog()
            unmuteBeep()
            _listening.value = false
            release()
            block()
        }
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (r == null) {
            onError(VoiceError("Speech recognition could not start on this phone. Install or update the Google app, or use Deepgram (Settings → Speech input)."))
            return
        }
        recognizer = r
        _partial.value = ""
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (id != session) return
                // Only now is the microphone really open: show "listening" from here on.
                _listening.value = true
                log("stt ready")
                // The start beep has played (muted) by now: give the user their sound back shortly after.
                handler.postDelayed(unmuteRunnable, 350)
            }
            override fun onBeginningOfSpeech() {
                if (id != session) return
                log("stt speech started")
                armWatchdog(id, SPEECH_MAX_MS) // the user is talking: allow a long sentence
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (id != session) return
                _listening.value = false
                armWatchdog(id, RESULT_WAIT_MS) // waiting for the final text
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (id != session) return
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { _partial.value = it }
            }

            override fun onResults(results: Bundle?) {
                // Some recognisers return an empty final result after good partial results: use the partial.
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    .takeUnless { it.isNullOrBlank() } ?: _partial.value.trim()
                finish {
                    log("stt result (${text.length} chars)")
                    if (text.isBlank()) onError(VoiceError("I didn't catch that.", noSpeech = true)) else onResult(text)
                }
            }

            override fun onError(error: Int) {
                finish {
                    log("stt error $error")
                    onError(mapError(error))
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            // Hinglish: let the recogniser also accept Indian English words in the same sentence.
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf(if (languageTag.startsWith("hi")) "en-IN" else "hi-IN"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        log("stt start $languageTag")
        muteBeep()
        armWatchdog(id, START_MAX_MS)
        try {
            r.startListening(intent)
        } catch (e: RuntimeException) {
            finish { onError(VoiceError("Speech recognition could not start (${e.javaClass.simpleName}).")) }
        }
        // Watchdog outcome: the recogniser went quiet without a result or an error.
        pendingTimeout = { finish { log("stt watchdog timeout"); onError(VoiceError("I didn't hear anything.", noSpeech = true)) } }
    }

    private var pendingTimeout: (() -> Unit)? = null

    private fun armWatchdog(id: Int, ms: Long) {
        cancelWatchdog()
        val w = Runnable { if (id == session) pendingTimeout?.invoke() }
        watchdog = w
        handler.postDelayed(w, ms)
    }

    private fun cancelWatchdog() {
        watchdog?.let { handler.removeCallbacks(it) }
        watchdog = null
    }

    // ------------------------------------------------------------ start-beep muting
    // Google's recogniser plays a "tu-dung" beep every time it starts. In hands-free mode it restarts
    // often, so the streams that carry the beep are muted for a moment around each start and always
    // restored (also by a safety timer). The muted streams are also written down, so that if the app
    // is killed while they are muted they are restored at the next start (otherwise Seema's own voice,
    // which plays on the media stream, would stay silent).
    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val prefs = context.getSharedPreferences("voice_input", Context.MODE_PRIVATE)
    private var mutedStreams: List<Int> = emptyList()
    private val unmuteRunnable = Runnable { unmuteBeep() }

    init {
        // Recover from a crash or kill while the beep streams were muted.
        prefs.getString(KEY_MUTED, null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.isNotEmpty() }?.let { leftover ->
            leftover.forEach { stream -> runCatching { audio?.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) } }
            prefs.edit().remove(KEY_MUTED).apply()
            log("restored ${leftover.size} stream(s) left muted by a previous run")
        }
    }

    private fun muteBeep() {
        val am = audio ?: return
        if (mutedStreams.isNotEmpty()) return
        mutedStreams = BEEP_STREAMS.filter { stream ->
            !am.isStreamMute(stream) && runCatching { am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0) }.isSuccess
        }
        if (mutedStreams.isNotEmpty()) prefs.edit().putString(KEY_MUTED, mutedStreams.joinToString(",")).commit()
        handler.postDelayed(unmuteRunnable, 1_500) // safety net
    }

    private fun unmuteBeep() {
        handler.removeCallbacks(unmuteRunnable)
        val am = audio ?: return
        if (mutedStreams.isEmpty()) return
        mutedStreams.forEach { stream -> runCatching { am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) } }
        mutedStreams = emptyList()
        prefs.edit().remove(KEY_MUTED).apply()
    }

    override fun stop() {
        session++ // any callback still on its way belongs to an old session now
        cancelWatchdog()
        pendingTimeout = null
        unmuteBeep()
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
        // Happen when the recogniser is restarted quickly: harmless, just listen again.
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> VoiceError("The speech recogniser was busy.", transient = true)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceError("Microphone permission denied.", permissionDenied = true)
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceError("Speech recognition needs internet (network error).")
        SpeechRecognizer.ERROR_AUDIO -> VoiceError("Microphone audio error. Another app may be using the mic.")
        SpeechRecognizer.ERROR_SERVER -> VoiceError("Speech recognition server error.")
        ERROR_TOO_MANY_REQUESTS -> VoiceError("Speech recognition is rate-limited right now. Wait a minute, or use Deepgram (Settings → Speech input).")
        ERROR_SERVER_DISCONNECTED -> VoiceError("The speech service disconnected.", transient = true)
        ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE ->
            VoiceError("Hindi speech recognition is not available on this phone. In the Google app → Settings → Voice → Offline speech recognition, add Hindi; or use Deepgram (Settings → Speech input).")
        else -> VoiceError("Speech recognition error ($code).")
    }

    private companion object {
        val BEEP_STREAMS = listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
        const val KEY_MUTED = "muted_streams"
        // SpeechRecognizer error codes added in Android 12 (API 31).
        const val ERROR_TOO_MANY_REQUESTS = 10
        const val ERROR_SERVER_DISCONNECTED = 11
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
        /** No callback at all after starting (a hung recogniser). */
        const val START_MAX_MS = 12_000L
        /** The user is talking: longest sentence we wait for. */
        const val SPEECH_MAX_MS = 30_000L
        /** After the user stopped talking, the final text must arrive within this time. */
        const val RESULT_WAIT_MS = 8_000L
    }
}
