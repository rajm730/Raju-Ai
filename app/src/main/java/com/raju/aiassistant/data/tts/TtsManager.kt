package com.raju.aiassistant.data.tts

import com.raju.aiassistant.domain.assistant.SpeakResult
import com.raju.aiassistant.domain.assistant.Speaker
import com.raju.aiassistant.domain.model.UserSettings
import com.raju.aiassistant.domain.voice.SpeechPipeline
import com.raju.aiassistant.domain.voice.TtsProvider
import com.raju.aiassistant.domain.voice.providerOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Speech output. One utterance at a time: a new speak() or stop() interrupts the current one,
 * so responses are never spoken twice or on top of each other. Engines are tried in the order
 * chosen in Settings (selected engine first, then the rest); a failing engine falls back to the
 * next one and the reason is reported. Lives in the app scope, so it survives screen rotation.
 */
class TtsManager(
    private val scope: CoroutineScope,
    private val pipeline: SpeechPipeline,
    private val providers: List<TtsProvider>,
    private val androidTts: AndroidTts,
    private val settings: StateFlow<UserSettings>,
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
            lock.withLock {
                _speaking.value = true
                try {
                    val s = settings.value
                    val ordered = providerOrder(s.ttsEngine).mapNotNull { e -> providers.firstOrNull { it.engine == e } }
                    result = pipeline.speak(
                        text = text,
                        providers = ordered,
                        preferredLanguage = s.speechLanguage,
                        rate = s.speechRate,
                        onEngine = { _activeEngine.value = it },
                        onLevel = { _level.value = it },
                    )
                } finally {
                    _speaking.value = false
                    _level.value = 0f
                }
            }
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

    /** Settings → Test voice. Really synthesises and plays a sample. */
    fun test(sample: String = "Namaste! Main Seema AI hoon. आपकी क्या मदद कर सकती हूँ?", onDone: (SpeakResult) -> Unit) {
        scope.launch { onDone(speak(sample)) }
    }
}
