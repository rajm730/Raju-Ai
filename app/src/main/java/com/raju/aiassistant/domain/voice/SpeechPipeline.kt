package com.raju.aiassistant.domain.voice

import com.raju.aiassistant.core.audio.PcmAudio
import com.raju.aiassistant.domain.ai.AiResult
import com.raju.aiassistant.domain.assistant.SpeakResult
import com.raju.aiassistant.domain.assistant.chunkForSpeech
import com.raju.aiassistant.domain.model.TtsEngine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** One text-to-speech engine (cloud or on-device). New engines (e.g. an offline neural voice) implement this. */
interface TtsProvider {
    val engine: TtsEngine
    val label: String

    /** False when the provider can't be used right now (no key, offline, not installed). Skipped silently. */
    suspend fun isAvailable(): Boolean

    suspend fun synthesize(text: String, languageTag: String, rate: Float): AiResult<PcmAudio>
}

/** Audio output used by the pipeline (AudioTrack on Android, a fake in tests). */
interface PcmSink {
    fun requestFocus()
    fun abandonFocus()
    suspend fun play(audio: PcmAudio, onLevel: (Float) -> Unit)
}

/**
 * True when synthesised audio is far too short or quiet for the words it should contain: about
 * 40 ms of sound per letter is expected; under a quarter of that (or pure silence) means the voice
 * could not read the text (e.g. Devanagari given to an English-only voice).
 */
fun isSilent(audio: PcmAudio, text: String): Boolean {
    val letters = text.count { it.isLetterOrDigit() }
    if (letters < 3 || audio.sampleRate <= 0) return false
    val seconds = audio.frameCount.toDouble() / audio.sampleRate
    if (seconds < letters * 0.010) return true
    return com.raju.aiassistant.core.audio.pcmLevel(audio.data, 0, audio.data.size) < 0.002f
}

/** The user's chosen engine first, then the others in their default order — so there is always a fallback. */
fun providerOrder(selected: TtsEngine): List<TtsEngine> = listOf(selected) + TtsEngine.entries.filter { it != selected }

private val DEVANAGARI = Regex("[\\u0900-\\u097F]")

/**
 * Language for one chunk: Devanagari text is always spoken as Hindi (hi-IN); Roman-script text
 * (Hinglish/English) uses the user's chosen language, so a Hindi voice reads Hinglish naturally.
 */
fun languageFor(chunk: String, preferredTag: String): String = if (DEVANAGARI.containsMatchIn(chunk)) "hi-IN" else preferredTag

/**
 * Speaks text chunk by chunk. While one chunk plays the next is already being synthesised
 * (one chunk prefetch), so there are no long pauses between sentences. If a provider fails it is
 * dropped for the rest of the utterance and the next provider takes over from the same chunk.
 * Cancelling the calling coroutine stops synthesis and playback immediately.
 */
class SpeechPipeline(
    private val sink: PcmSink,
    /** Detects a voice that "succeeded" but produced no real sound (see [isSilent]). */
    private val silent: (PcmAudio, String) -> Boolean = ::isSilent,
) {

    suspend fun speak(
        text: String,
        providers: List<TtsProvider>,
        preferredLanguage: String,
        rate: Float,
        onEngine: (String) -> Unit = {},
        onLevel: (Float) -> Unit = {},
        onChunkPlayed: () -> Unit = {},
    ): SpeakResult {
        val chunks = chunkForSpeech(text)
        if (chunks.isEmpty()) return SpeakResult(true, "none")
        val usable = providers.filter { runCatching { it.isAvailable() }.getOrDefault(false) }.toMutableList()
        if (usable.isEmpty()) return SpeakResult(false, "none", "No text-to-speech engine is available.")

        var fallbackNote: String? = null
        var lastLabel = usable.first().label
        val ready = Channel<Result<Pair<String, PcmAudio>>>(capacity = 1)

        return coroutineScope {
            val producer = launch {
                for (chunk in chunks) {
                    var audio: PcmAudio? = null
                    while (audio == null && usable.isNotEmpty()) {
                        val provider = usable.first()
                        when (val r = provider.synthesize(chunk, languageFor(chunk, preferredLanguage), rate)) {
                            // A voice that returns (almost) no sound for real words has failed, even if it says OK.
                            is AiResult.Success -> if (silent(r.value, chunk)) {
                                usable.removeAt(0)
                                fallbackNote = "${provider.label} returned no sound" + (usable.firstOrNull()?.let { " — using ${it.label}." } ?: ".")
                            } else audio = r.value
                            is AiResult.Failure -> {
                                usable.removeAt(0)
                                fallbackNote = "${provider.label} failed (${r.message})" +
                                    (usable.firstOrNull()?.let { " — using ${it.label}." } ?: ".")
                            }
                        }
                    }
                    if (audio == null) {
                        ready.send(Result.failure(IllegalStateException(fallbackNote ?: "Speech synthesis failed.")))
                        break
                    }
                    ready.send(Result.success(usable.first().label to audio))
                }
                ready.close()
            }

            sink.requestFocus()
            try {
                for (item in ready) {
                    val (label, audio) = item.getOrElse { e ->
                        producer.cancel()
                        return@coroutineScope SpeakResult(false, lastLabel, e.message)
                    }
                    lastLabel = label
                    onEngine(label)
                    try {
                        sink.play(audio, onLevel)
                    } catch (e: IllegalStateException) {
                        producer.cancel()
                        return@coroutineScope SpeakResult(false, lastLabel, "The phone's audio output failed (${e.message}).")
                    }
                    onChunkPlayed()
                }
                SpeakResult(true, lastLabel, fallbackNote)
            } finally {
                onLevel(0f)
                sink.abandonFocus()
            }
        }
    }
}
