package com.raju.aiassistant.data.tts

import com.raju.aiassistant.data.ai.JsonFormat
import com.raju.aiassistant.data.ai.fetch
import com.raju.aiassistant.data.ai.httpFailure
import com.raju.aiassistant.data.ai.toRequestBody
import com.raju.aiassistant.data.ai.withRetry
import com.raju.aiassistant.domain.ai.AiResult
import com.raju.aiassistant.domain.ai.FailureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64

/** Sarvam AI Bulbul text-to-speech. Returns WAV bytes. */
class SarvamTtsClient(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.sarvam.ai",
    private val model: String = "bulbul:v2",
) {
    suspend fun synthesize(text: String, speaker: String, languageCode: String, pace: Float): AiResult<ByteArray> = withRetry {
        val payload = buildJsonObject {
            put("text", text)
            put("target_language_code", languageCode)
            put("speaker", speaker)
            put("model", model)
            put("pace", pace.coerceIn(0.5f, 2.0f).toDouble())
            put("speech_sample_rate", 22050)
        }
        val request = Request.Builder()
            .url("$baseUrl/text-to-speech")
            .header("api-subscription-key", apiKey)
            .post(payload.toRequestBody())
            .build()
        when (val r = http.fetch(request)) {
            is AiResult.Failure -> r
            is AiResult.Success -> {
                val (code, body) = r.value
                if (code !in 200..299) httpFailure(code, body) else parse(body)
            }
        }
    }

    private fun parse(body: String): AiResult<ByteArray> {
        val root = runCatching { JsonFormat.parseToJsonElement(body) as JsonObject }.getOrNull()
        val audio = ((root?.get("audios") as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull
            ?: return AiResult.Failure(FailureKind.UNKNOWN, "Sarvam returned no audio")
        return try {
            AiResult.Success(Base64.getDecoder().decode(audio))
        } catch (e: IllegalArgumentException) {
            AiResult.Failure(FailureKind.UNKNOWN, "Sarvam audio could not be decoded")
        }
    }
}
