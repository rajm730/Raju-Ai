package com.raju.aiassistant.data.tts

import com.raju.aiassistant.data.ai.JsonFormat
import com.raju.aiassistant.data.ai.fetch
import com.raju.aiassistant.data.ai.fetchBytes
import com.raju.aiassistant.data.ai.httpFailure
import com.raju.aiassistant.data.ai.toRequestBody
import com.raju.aiassistant.data.ai.withRetry
import com.raju.aiassistant.domain.ai.AiResult
import com.raju.aiassistant.domain.ai.FailureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Deepgram (https://deepgram.com): Aura text-to-speech (English voices) and Nova speech-to-text (Hindi/Hinglish). */
class DeepgramClient(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepgram.com",
) {
    /** Returns 24 kHz 16-bit WAV. */
    suspend fun synthesize(text: String, voice: String): AiResult<ByteArray> = withRetry {
        val url = "$baseUrl/v1/speak".toHttpUrl().newBuilder()
            .addQueryParameter("model", voice)
            .addQueryParameter("encoding", "linear16")
            .addQueryParameter("container", "wav")
            .addQueryParameter("sample_rate", "24000")
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Token $apiKey")
            .post(buildJsonObject { put("text", text) }.toRequestBody()).build()
        when (val r = http.fetchBytes(request)) {
            is AiResult.Failure -> r
            is AiResult.Success -> {
                val (code, body) = r.value
                if (code !in 200..299) httpFailure(code, String(body)) else AiResult.Success(body)
            }
        }
    }

    /** Transcribes a short WAV recording. [language] "multi" handles Hindi and English mixed in one sentence. */
    suspend fun transcribe(wav: ByteArray, model: String, language: String): AiResult<String> =
        when (val r = transcribeDetailed(wav, model, language)) {
            is AiResult.Success -> AiResult.Success(r.value.text)
            is AiResult.Failure -> r
        }

    /**
     * Like [transcribe], with the model's confidence. [keyterms] (names like "Seema" or contacts) are
     * boosted on Nova-3; if the service rejects them, the request is repeated without.
     */
    suspend fun transcribeDetailed(wav: ByteArray, model: String, language: String, keyterms: List<String> = emptyList()): AiResult<Transcript> {
        val terms = if (model.startsWith("nova-3")) keyterms.filter { it.isNotBlank() }.distinct().take(MAX_KEYTERMS) else emptyList()
        val first = listen(wav, model, language, terms)
        return if (first is AiResult.Failure && terms.isNotEmpty() && first.message.contains("HTTP 400")) listen(wav, model, language, emptyList()) else first
    }

    private suspend fun listen(wav: ByteArray, model: String, language: String, keyterms: List<String>): AiResult<Transcript> = withRetry {
        val url = "$baseUrl/v1/listen".toHttpUrl().newBuilder()
            .addQueryParameter("model", model)
            .addQueryParameter("language", language)
            .addQueryParameter("smart_format", "true")
            .addQueryParameter("punctuate", "true")
            .apply { keyterms.forEach { addQueryParameter("keyterm", it) } }
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Token $apiKey")
            .post(wav.toRequestBody(WAV)).build()
        when (val r = http.fetch(request)) {
            is AiResult.Failure -> r
            is AiResult.Success -> {
                val (code, body) = r.value
                if (code !in 200..299) httpFailure(code, body)
                else parseTranscript(body)?.let { AiResult.Success(it) } ?: AiResult.Failure(FailureKind.UNKNOWN, "Deepgram returned an unreadable transcript")
            }
        }
    }

    private fun parseTranscript(body: String): Transcript? {
        val root = runCatching { JsonFormat.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return null
        val channel = ((root["results"] as? JsonObject)?.get("channels") as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val alt = (channel["alternatives"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val confidence = (alt["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 1.0
        return Transcript(alt.str("transcript").orEmpty().trim(), confidence)
    }

    private companion object {
        val WAV = "audio/wav".toMediaType()
        const val MAX_KEYTERMS = 40
    }
}

/** What Deepgram heard and how sure it is (0..1). */
data class Transcript(val text: String, val confidence: Double)
