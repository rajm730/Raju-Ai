package com.raju.aiassistant.data.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

data class VoiceOption(val name: String, val label: String)

enum class LanguageSupport(val label: String) {
    AVAILABLE("available"),
    MISSING_DATA("voice data not installed"),
    NOT_SUPPORTED("not supported by this engine"),
    ENGINE_UNAVAILABLE("no TTS engine"),
}

data class TtsDiagnostics(val engine: String?, val hindi: LanguageSupport, val englishIndia: LanguageSupport)

/** Android's built-in TextToSpeech, used offline and as fallback. Synthesises to WAV for unified playback. */
class AndroidTts(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val waiting = ConcurrentHashMap<String, (Boolean) -> Unit>()

    private suspend fun engine(): TextToSpeech? {
        val existing = ready
        if (existing != null) return if (existing.await()) tts else null
        val deferred = CompletableDeferred<Boolean>()
        ready = deferred
        withContext(Dispatchers.Main) {
            tts = TextToSpeech(context.applicationContext) { status -> deferred.complete(status == TextToSpeech.SUCCESS) }.apply {
                setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { utteranceId?.let { waiting.remove(it)?.invoke(true) } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { utteranceId?.let { waiting.remove(it)?.invoke(false) } }
                    override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { waiting.remove(it)?.invoke(false) } }
                })
            }
        }
        val ok = deferred.await()
        if (!ok) { ready = null; tts?.shutdown(); tts = null }
        return if (ok) tts else null
    }

    /**
     * What the phone's TTS engine really supports (checked with isLanguageAvailable, not assumed).
     * [engine] is the engine package, e.g. com.google.android.tts or com.samsung.SMT.
     */
    suspend fun diagnostics(): TtsDiagnostics {
        val e = engine() ?: return TtsDiagnostics(null, LanguageSupport.ENGINE_UNAVAILABLE, LanguageSupport.ENGINE_UNAVAILABLE)
        fun check(tag: String) = when (runCatching { e.isLanguageAvailable(Locale.forLanguageTag(tag)) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)) {
            TextToSpeech.LANG_AVAILABLE, TextToSpeech.LANG_COUNTRY_AVAILABLE, TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> LanguageSupport.AVAILABLE
            TextToSpeech.LANG_MISSING_DATA -> LanguageSupport.MISSING_DATA
            else -> LanguageSupport.NOT_SUPPORTED
        }
        return TtsDiagnostics(runCatching { e.defaultEngine }.getOrNull(), check("hi-IN"), check("en-IN"))
    }

    /** Voices installed for Hindi and English (India), as reported by the TTS engine. */
    suspend fun voices(): List<VoiceOption> {
        val engine = engine() ?: return emptyList()
        return runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language in setOf("hi", "en") && (it.locale.country == "IN" || it.locale.language == "hi") }
            .filterNot { it.isNetworkConnectionRequired && it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
            .sortedWith(compareBy<Voice> { it.locale.language != "hi" }.thenBy { it.name })
            .map { VoiceOption(it.name, "${it.locale.displayName} — ${it.name}" + if (it.isNetworkConnectionRequired) " (online)" else "") }
    }

    /** Returns WAV bytes, or null with a reason. */
    suspend fun synthesize(text: String, languageTag: String, voiceName: String?, rate: Float): Pair<ByteArray?, String?> {
        val engine = engine() ?: return null to "Android text-to-speech is not available. Install Google Speech Services."
        val locale = Locale.forLanguageTag(languageTag)
        // A voice chosen in Settings is used only for chunks in that voice's language
        // (e.g. a Hindi voice is not forced onto an English sentence).
        val voice = voiceName?.let { name -> runCatching { engine.voices?.firstOrNull { it.name == name } }.getOrNull() }
            ?.takeIf { it.locale.language == locale.language }
        if (voice != null) engine.setVoice(voice) else {
            var result = engine.setLanguage(locale)
            if (result < TextToSpeech.LANG_AVAILABLE && locale.country.isNotEmpty()) result = engine.setLanguage(Locale(locale.language))
            if (result < TextToSpeech.LANG_AVAILABLE) {
                // An English voice reads Devanagari as silence: say what is missing instead of "speaking" nothing.
                if (locale.language == "hi" || text.any { it in '\u0900'..'\u097F' }) {
                    return null to if (result == TextToSpeech.LANG_MISSING_DATA)
                        "the phone's Hindi voice is not installed (Settings → Text-to-speech → Google → Install voice data → Hindi)"
                    else "the phone's text-to-speech engine has no Hindi voice (install Google Speech Services)"
                }
                engine.setLanguage(Locale.forLanguageTag("en-IN"))
            }
        }
        engine.setSpeechRate(rate)
        val file = File(context.cacheDir, "tts_${UUID.randomUUID()}.wav")
        val id = file.name
        return try {
            // Some engines never call back for an utterance: never wait forever (Seema would stay "speaking").
            val ok = withTimeoutOrNull(SYNTH_TIMEOUT_MS + text.length * 60L) { suspendCancellableCoroutine { cont ->
                waiting[id] = { success -> if (cont.isActive) cont.resume(success) }
                cont.invokeOnCancellation { waiting.remove(id); engine.stop() }
                if (engine.synthesizeToFile(text, null, file, id) != TextToSpeech.SUCCESS) {
                    waiting.remove(id)
                    cont.resume(false)
                }
            } }
            if (ok == null) {
                waiting.remove(id)
                engine.stop()
                return null to "Android text-to-speech did not respond."
            }
            if (ok && file.exists()) withContext(Dispatchers.IO) { file.readBytes() } to null
            else null to "Android text-to-speech failed to synthesise speech."
        } finally {
            file.delete()
        }
    }

    fun stop() {
        tts?.stop()
    }

    private companion object {
        const val SYNTH_TIMEOUT_MS = 15_000L
    }
}
