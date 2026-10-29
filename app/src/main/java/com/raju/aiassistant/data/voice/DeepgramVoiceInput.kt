package com.raju.aiassistant.data.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.raju.aiassistant.core.audio.pcm16ToWav
import com.raju.aiassistant.data.tts.DeepgramClient
import com.raju.aiassistant.domain.ai.AiResult
import com.raju.aiassistant.domain.assistant.VoiceError
import com.raju.aiassistant.domain.assistant.VoiceInput
import com.raju.aiassistant.domain.voice.EndpointDetector
import com.raju.aiassistant.domain.voice.SttModelOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Speech-to-text with Deepgram Nova (cloud). Records 16 kHz mono audio only while the user is
 * talking to the assistant (visible listening state), stops after a short pause, then sends that
 * one recording to Deepgram. Nothing is recorded in the background or stored.
 */
class DeepgramVoiceInput(
    private val context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val apiKey: suspend () -> String?,
    private val model: () -> SttModelOption,
    private val configured: () -> Boolean,
    /** Words to recognise especially well: "Seema", favourite contacts' names. */
    private val keyterms: suspend () -> List<String> = { emptyList() },
) : VoiceInput {
    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private val _partial = MutableStateFlow("")
    override val partial: StateFlow<String> = _partial.asStateFlow()
    private var job: Job? = null

    override fun isAvailable(): Boolean = configured()

    override fun capturesInApp() = true

    /** The recorder in use, so [stop] can release the microphone at once (not after the next frame). */
    @Volatile private var activeRecorder: AudioRecord? = null

    override fun start(languageTag: String, onResult: (String) -> Unit, onError: (VoiceError) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError(VoiceError("Microphone permission is needed for voice. Allow it when asked, or in Settings → Apps → Seema AI → Permissions.", permissionDenied = true))
            return
        }
        stop()
        job = scope.launch {
            _partial.value = ""
            _listening.value = true
            val pcm = try {
                withContext(Dispatchers.IO) { record() }
            } catch (e: MicrophoneException) {
                onError(VoiceError("The microphone could not be started (${e.message}). Another app may be using it."))
                return@launch
            } finally {
                _listening.value = false
            }
            if (pcm == null) {
                onError(VoiceError("I didn't catch that.", noSpeech = true))
                return@launch
            }
            _partial.value = "…"
            val key = apiKey()
            if (key.isNullOrBlank()) {
                onError(VoiceError("Deepgram API key is not set (Settings → Deepgram key)."))
                return@launch
            }
            val option = model()
            when (val r = DeepgramClient(http, key).transcribeDetailed(pcm16ToWav(pcm, SAMPLE_RATE), option.model, option.language, keyterms())) {
                is AiResult.Success -> {
                    val heard = r.value
                    _partial.value = heard.text
                    when {
                        heard.text.isBlank() -> onError(VoiceError("I didn't catch that.", noSpeech = true))
                        // Very unsure: better to ask again than to act on a wrong word.
                        heard.confidence < MIN_CONFIDENCE -> onError(VoiceError("I didn't hear that clearly.", unclear = true))
                        else -> onResult(heard.text)
                    }
                }
                is AiResult.Failure -> onError(VoiceError("Deepgram: ${r.message}"))
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        // Free the microphone right now, so the next recogniser (or barge-in) gets real audio.
        activeRecorder?.let { runCatching { it.stop() } }
        _listening.value = false
    }

    /** Records until the user stops talking. Returns null when no speech was heard. */
    @SuppressLint("MissingPermission") // checked in start()
    private suspend fun record(): ByteArray? {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw MicrophoneException("audio format not supported")
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FRAME_BYTES * 10),
            )
        } catch (e: IllegalArgumentException) {
            throw MicrophoneException(e.message ?: "invalid audio settings")
        } catch (e: SecurityException) {
            throw MicrophoneException("permission denied")
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw MicrophoneException("recorder not initialised")
        }
        val detector = EndpointDetector(frameMs = FRAME_MS)
        val out = ByteArrayOutputStream()
        val frame = ByteArray(FRAME_BYTES)
        try {
            try {
                recorder.startRecording()
                activeRecorder = recorder
            } catch (e: IllegalStateException) {
                throw MicrophoneException("recording could not start")
            }
            while (true) {
                coroutineContext.ensureActive()
                var read = 0
                while (read < FRAME_BYTES) {
                    val n = recorder.read(frame, read, FRAME_BYTES - read)
                    coroutineContext.ensureActive() // stopped while reading: not a microphone failure
                    if (n <= 0) throw MicrophoneException("microphone read failed ($n)")
                    read += n
                }
                out.write(frame)
                when (detector.onFrame(rms(frame))) {
                    EndpointDetector.State.DONE -> return out.toByteArray()
                    EndpointDetector.State.NO_SPEECH -> return null
                    else -> Unit
                }
            }
        } finally {
            if (activeRecorder === recorder) activeRecorder = null
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private fun rms(frame: ByteArray): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < frame.size) {
            val s = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xFF)).toShort().toDouble()
            sum += s * s
            i += 2
        }
        return sqrt(sum / (frame.size / 2))
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_BYTES = SAMPLE_RATE / 1000 * FRAME_MS * 2 // 640 bytes = 20 ms of 16-bit mono
        const val MIN_CONFIDENCE = 0.45
    }
}

private class MicrophoneException(message: String) : Exception(message)

/** Uses Android's recogniser or Deepgram, whichever is chosen in Settings at the moment listening starts. */
class SwitchingVoiceInput(
    private val android: VoiceInput,
    private val deepgram: VoiceInput,
    private val useDeepgram: () -> Boolean,
    scope: CoroutineScope,
) : VoiceInput {
    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private val _partial = MutableStateFlow("")
    override val partial: StateFlow<String> = _partial.asStateFlow()

    @Volatile private var active: VoiceInput = android

    init {
        scope.launch { android.listening.collect { if (active === android) _listening.value = it } }
        scope.launch { deepgram.listening.collect { if (active === deepgram) _listening.value = it } }
        scope.launch { android.partial.collect { if (active === android) _partial.value = it } }
        scope.launch { deepgram.partial.collect { if (active === deepgram) _partial.value = it } }
    }

    private fun current() = if (useDeepgram() && deepgram.isAvailable()) deepgram else android

    override fun isAvailable(): Boolean = current().isAvailable()

    /** Whether the input that will be used for the next [start] records inside this app. */
    override fun capturesInApp(): Boolean = current().capturesInApp()

    override fun start(languageTag: String, onResult: (String) -> Unit, onError: (VoiceError) -> Unit) {
        stop()
        active = current()
        _partial.value = ""
        active.start(languageTag, onResult, onError)
    }

    override fun stop() {
        android.stop()
        deepgram.stop()
    }
}
