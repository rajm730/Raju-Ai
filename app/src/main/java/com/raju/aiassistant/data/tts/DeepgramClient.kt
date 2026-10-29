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
    suspend fun transcribe(wav: ByteArray, model: String, language: String): AiResult<String> = withRetry {
        val url = "$baseUrl/v1/listen".toHttpUrl().newBuilder()
            .addQueryParameter("model", model)
            .addQueryParameter("language", language)
            .addQueryParameter("smart_format", "true")
            .addQueryParameter("punctuate", "true")
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

    private fun parseTranscript(body: String): String? {
        val root = runCatching { JsonFormat.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return null
        val channel = ((root["results"] as? JsonObject)?.get("channels") as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val alt = (channel["alternatives"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        return alt.str("transcript").orEmpty().trim()
    }

    private companion object {
        val WAV = "audio/wav".toMediaType()
    }
}
