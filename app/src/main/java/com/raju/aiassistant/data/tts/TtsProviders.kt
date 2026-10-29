package com.raju.aiassistant.data.tts

import com.raju.aiassistant.core.audio.PcmAudio
import com.raju.aiassistant.core.audio.parseWav
import com.raju.aiassistant.domain.ai.AiResult
import com.raju.aiassistant.domain.ai.FailureKind
import com.raju.aiassistant.domain.model.SecretId
import com.raju.aiassistant.domain.model.TtsEngine
import com.raju.aiassistant.domain.model.UserSettings
import com.raju.aiassistant.domain.voice.CloudVoice
import com.raju.aiassistant.domain.voice.TtsProvider
import com.raju.aiassistant.domain.voice.VoiceCatalog
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

private fun wavOrFailure(r: AiResult<ByteArray>, provider: String): AiResult<PcmAudio> = when (r) {
    is AiResult.Success -> parseWav(r.value)?.let { AiResult.Success(it) }
        ?: AiResult.Failure(FailureKind.UNKNOWN, "$provider returned audio in an unsupported format")
    is AiResult.Failure -> r
}

/** ElevenLabs (cloud). If no voice was picked yet, the best female voice of the account is chosen and saved. */
class ElevenLabsTtsProvider(
    private val http: OkHttpClient,
    private val secret: suspend (SecretId) -> String?,
    private val isOnline: () -> Boolean,
    private val settings: () -> UserSettings,
    private val rememberVoice: suspend (CloudVoice) -> Unit,
) : TtsProvider {
    override val engine = TtsEngine.ELEVENLABS
    override val label = "ElevenLabs (cloud)"

    override suspend fun isAvailable() = isOnline() && !secret(SecretId.ELEVENLABS_API_KEY).isNullOrBlank()

    override suspend fun synthesize(text: String, languageTag: String, rate: Float): AiResult<PcmAudio> {
        val key = secret(SecretId.ELEVENLABS_API_KEY) ?: return AiResult.Failure(FailureKind.NOT_CONFIGURED, "ElevenLabs API key is not set")
        val client = ElevenLabsClient(http, key)
        val s = settings()
        val voice = s.elevenVoiceId ?: when (val r = client.listVoices()) {
            is AiResult.Success -> VoiceCatalog.bestDefault(r.value)?.also { rememberVoice(it) }?.id
            is AiResult.Failure -> null
        } ?: VoiceCatalog.ELEVEN_FALLBACK_VOICE
        return wavOrFailure(client.synthesize(text, voice, s.elevenModel, rate), "ElevenLabs")
    }
}

/** Cartesia Sonic (cloud). Needs a voice ID: if none was picked yet, a Hindi female voice is chosen and saved. */
class CartesiaTtsProvider(
    private val http: OkHttpClient,
    private val secret: suspend (SecretId) -> String?,
    private val isOnline: () -> Boolean,
    private val settings: () -> UserSettings,
    private val rememberVoice: suspend (CloudVoice) -> Unit,
) : TtsProvider {
    override val engine = TtsEngine.CARTESIA
    override val label = "Cartesia (cloud)"

    override suspend fun isAvailable() = isOnline() && !secret(SecretId.CARTESIA_API_KEY).isNullOrBlank()

    override suspend fun synthesize(text: String, languageTag: String, rate: Float): AiResult<PcmAudio> {
        val key = secret(SecretId.CARTESIA_API_KEY) ?: return AiResult.Failure(FailureKind.NOT_CONFIGURED, "Cartesia API key is not set")
        val client = CartesiaClient(http, key)
        val s = settings()
        val voice = s.cartesiaVoiceId ?: when (val r = client.listVoices(maxPages = 2)) {
            is AiResult.Success -> VoiceCatalog.bestDefault(r.value)?.also { rememberVoice(it) }?.id
                ?: return AiResult.Failure(FailureKind.NOT_CONFIGURED, "No Cartesia voice found — pick one in Settings → Voice")
            is AiResult.Failure -> return r
        }
        val language = languageTag.substringBefore('-').lowercase().ifBlank { "hi" }
        return wavOrFailure(client.synthesize(text, voice, s.cartesiaModel, language, rate, s.cartesiaMood), "Cartesia")
    }
}

/** Deepgram Aura (cloud). Aura has English voices only, so it is skipped while Hindi is the speech language. */
class DeepgramTtsProvider(
    private val http: OkHttpClient,
    private val secret: suspend (SecretId) -> String?,
    private val isOnline: () -> Boolean,
    private val settings: () -> UserSettings,
) : TtsProvider {
    override val engine = TtsEngine.DEEPGRAM
    override val label = "Deepgram Aura (cloud, English)"

    override suspend fun isAvailable() =
        isOnline() && !settings().speechLanguage.startsWith("hi") && !secret(SecretId.DEEPGRAM_API_KEY).isNullOrBlank()

    override suspend fun synthesize(text: String, languageTag: String, rate: Float): AiResult<PcmAudio> {
        if (languageTag.startsWith("hi")) return AiResult.Failure(FailureKind.UNKNOWN, "Deepgram voices speak English only")
        val key = secret(SecretId.DEEPGRAM_API_KEY) ?: return AiResult.Failure(FailureKind.NOT_CONFIGURED, "Deepgram API key is not set")
        return wavOrFailure(DeepgramClient(http, key).synthesize(text, settings().deepgramVoice), "Deepgram")
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
