package com.raju.aiassistant.data.tts

import com.raju.aiassistant.core.audio.PcmAudio
import com.raju.aiassistant.core.audio.parseWav
import com.raju.aiassistant.domain.ai.AiResult
import com.raju.aiassistant.domain.ai.FailureKind
import com.raju.aiassistant.domain.model.SecretId
import com.raju.aiassistant.domain.model.TtsEngine
import com.raju.aiassistant.domain.voice.TtsProvider
import okhttp3.OkHttpClient

/** Sarvam AI Bulbul (cloud). Usable only with a saved key and an internet connection. */
class SarvamTtsProvider(
    private val http: OkHttpClient,
    private val secret: suspend (SecretId) -> String?,
    private val isOnline: () -> Boolean,
    private val speaker: () -> String,
) : TtsProvider {
    override val engine = TtsEngine.SARVAM
    override val label = "Sarvam AI (cloud)"

    override suspend fun isAvailable() = isOnline() && !secret(SecretId.SARVAM_API_KEY).isNullOrBlank()

    override suspend fun synthesize(text: String, languageTag: String, rate: Float): AiResult<PcmAudio> {
        val key = secret(SecretId.SARVAM_API_KEY) ?: return AiResult.Failure(FailureKind.NOT_CONFIGURED, "Sarvam API key is not set")
        return when (val r = SarvamTtsClient(http, key).synthesize(text, speaker(), languageTag, rate)) {
            is AiResult.Success -> parseWav(r.value)?.let { AiResult.Success(it) }
                ?: AiResult.Failure(FailureKind.UNKNOWN, "Sarvam returned audio in an unsupported format")
            is AiResult.Failure -> r
        }
    }
}

/** Android's built-in TextToSpeech (offline once voice data is installed). Always available as the last fallback. */
class AndroidTtsProvider(
    private val tts: AndroidTts,
    private val voiceName: () -> String?,
) : TtsProvider {
    override val engine = TtsEngine.ANDROID
    override val label = "Android TTS (offline)"

    override suspend fun isAvailable() = true

    override suspend fun synthesize(text: String, languageTag: String, rate: Float): AiResult<PcmAudio> {
        val (bytes, error) = tts.synthesize(text, languageTag, voiceName(), rate)
        if (bytes == null) return AiResult.Failure(FailureKind.UNKNOWN, error ?: "Android text-to-speech failed")
        return parseWav(bytes)?.let { AiResult.Success(it) }
            ?: AiResult.Failure(FailureKind.UNKNOWN, "Android TTS produced audio in an unsupported format")
    }
}
