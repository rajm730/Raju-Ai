package com.raju.aiassistant.data.tts

import com.raju.aiassistant.core.audio.AudioPlayer
import com.raju.aiassistant.core.audio.parseWav
import com.raju.aiassistant.domain.ai.AiResult
import com.raju.aiassistant.domain.assistant.SpeakResult
import com.raju.aiassistant.domain.assistant.Speaker
import com.raju.aiassistant.domain.assistant.chunkForSpeech
import com.raju.aiassistant.domain.model.SecretId
import com.raju.aiassistant.domain.model.TtsEngine
import com.raju.aiassistant.domain.model.UserSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * Speech output. One utterance at a time: a new speak() or stop() interrupts the current one,
 * so responses are never spoken twice or on top of each other. Sarvam (cloud) is used when
 * selected and configured; otherwise, or when it fails, Android TTS (offline) is used and the
 * failure is reported.
 */
class TtsManager(
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val player: AudioPlayer,
    private val androidTts: AndroidTts,
    private val settings: StateFlow<UserSettings>,
    private val secret: suspend (SecretId) -> String?,
    private val isOnline: () -> Boolean,
) : Speaker {
    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()
    private val _activeEngine = MutableStateFlow<String?>(null)
    /** Label of the engine used for the current/last utterance. */
    val activeEngine: StateFlow<String?> = _activeEngine.asStateFlow()

    private val lock = Mutex()
    private var current: Job? = null

    override suspend fun speak(text: String): SpeakResult {
        stop()
        var result = SpeakResult(false, "none")
        val job = scope.launch {
            lock.withLock { result = speakInternal(text) }
        }
        current = job
        try {
            job.join()
        } catch (e: CancellationException) {
            job.cancel() // caller was stopped: stop the audio too
            throw e
        }
        return if (job.isCancelled) result.copy(completed = false) else result
    }

    override fun stop() {
        current?.cancel()
        current = null
        androidTts.stop()
        _level.value = 0f
    }

    private suspend fun speakInternal(text: String): SpeakResult {
        val chunks = chunkForSpeech(text)
        if (chunks.isEmpty()) return SpeakResult(true, "none")
        val s = settings.value
        val sarvamKey = if (s.ttsEngine == TtsEngine.SARVAM && isOnline()) secret(SecretId.SARVAM_API_KEY) else null
        var useSarvam = !sarvamKey.isNullOrBlank()
        // Not configured / offline is an expected, silent fallback (the active engine label shows it);
        // only a real Sarvam failure is reported back.
        var error: String? = null
        _speaking.value = true
        player.requestFocus()
        try {
            for (chunk in chunks) {
                var wav: ByteArray? = null
                if (useSarvam) {
                    _activeEngine.value = "Sarvam AI (cloud)"
                    when (val r = SarvamTtsClient(http, sarvamKey!!).synthesize(chunk, s.sarvamSpeaker, s.speechLanguage, s.speechRate)) {
                        is AiResult.Success -> wav = r.value
                        is AiResult.Failure -> {
                            error = "Sarvam voice failed (${r.message}) — using Android voice."
                            useSarvam = false
                        }
                    }
                }
                if (wav == null) {
                    _activeEngine.value = "Android TTS (offline)"
                    val (bytes, err) = androidTts.synthesize(chunk, s.speechLanguage, s.androidVoice, s.speechRate)
                    if (bytes == null) return SpeakResult(false, "Android TTS", err)
                    wav = bytes
                }
                val pcm = parseWav(wav) ?: return SpeakResult(false, _activeEngine.value ?: "?", "Unsupported audio format from TTS engine.")
                player.play(pcm) { _level.value = it }
            }
            return SpeakResult(true, _activeEngine.value ?: "?", error)
        } finally {
            _speaking.value = false
            _level.value = 0f
            player.abandonFocus()
        }
    }

    /** Settings → Test voice. Really synthesises and plays a sample. */
    fun test(sample: String = "Namaste! Main Raju AI hoon. Aapki kya madad kar sakti hoon?", onDone: (SpeakResult) -> Unit) {
        scope.launch { onDone(speak(sample)) }
    }
}
