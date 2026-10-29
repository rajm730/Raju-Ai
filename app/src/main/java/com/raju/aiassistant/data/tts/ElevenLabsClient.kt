package com.raju.aiassistant.data.tts

import com.raju.aiassistant.data.ai.JsonFormat
import com.raju.aiassistant.data.ai.fetch
import com.raju.aiassistant.data.ai.fetchBytes
import com.raju.aiassistant.data.ai.httpFailure
import com.raju.aiassistant.data.ai.toRequestBody
import com.raju.aiassistant.data.ai.withRetry
import com.raju.aiassistant.domain.ai.AiResult
import com.raju.aiassistant.domain.ai.FailureKind
import com.raju.aiassistant.domain.voice.CloudVoice
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/** ElevenLabs text-to-speech (https://elevenlabs.io). Returns 24 kHz 16-bit WAV. Key goes in the xi-api-key header. */
class ElevenLabsClient(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.elevenlabs.io",
) {
    suspend fun synthesize(text: String, voiceId: String, modelId: String, speed: Float): AiResult<ByteArray> = withRetry {
        val payload = buildJsonObject {
            put("text", text)
            put("model_id", modelId)
            putJsonObject("voice_settings") {
                // Slightly below the default stability gives a warmer, less flat delivery.
                put("stability", 0.45)
                put("similarity_boost", 0.8)
                put("style", 0.0)
                put("use_speaker_boost", true)
                put("speed", Math.round(speed.coerceIn(0.7f, 1.2f) * 100) / 100.0)
            }
        }
        val request = Request.Builder()
            .url("$baseUrl/v1/text-to-speech/$voiceId?output_format=wav_24000")
            .header("xi-api-key", apiKey)
            .header("Accept", "audio/wav")
            .post(payload.toRequestBody())
            .build()
        when (val r = http.fetchBytes(request)) {
            is AiResult.Failure -> r
            is AiResult.Success -> {
                val (code, body) = r.value
                if (code !in 200..299) httpFailure(code, String(body)) else AiResult.Success(body)
            }
        }
    }

    /** Text-to-speech models that can speak Hindi, as (id, name). */
    suspend fun listModels(): AiResult<List<Pair<String, String>>> = get("$baseUrl/v1/models") { body ->
        val models = JsonFormat.parseToJsonElement(body) as? JsonArray ?: return@get null
        models.mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            val id = o.str("model_id") ?: return@mapNotNull null
            if ((o["can_do_text_to_speech"] as? JsonPrimitive)?.booleanOrNull == false) return@mapNotNull null
            val langs = (o["languages"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("language_id") }
            if (langs.isNotEmpty() && "hi" !in langs) return@mapNotNull null
            id to (o.str("name") ?: id)
        }
    }

    /** The voices in the user's account (default voices included). */
    suspend fun listVoices(): AiResult<List<CloudVoice>> = get("$baseUrl/v2/voices?page_size=100") { body ->
        val root = JsonFormat.parseToJsonElement(body) as? JsonObject ?: return@get null
        (root["voices"] as? JsonArray).orEmpty().mapNotNull { v ->
            val o = v as? JsonObject ?: return@mapNotNull null
            val labels = o["labels"] as? JsonObject
            val label = { k: String -> labels?.str(k)?.lowercase().orEmpty() }
            val verified = (o["verified_languages"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("language") }
            CloudVoice(
                id = o.str("voice_id") ?: return@mapNotNull null,
                name = o.str("name") ?: "Voice",
                detail = listOf(label("accent"), label("age"), label("descriptive").ifBlank { label("description") })
                    .filter { it.isNotBlank() }.joinToString(", "),
                female = label("gender") == "female",
                hindi = "hi" in verified || label("language") == "hi" || "hindi" in label("accent"),
            )
        }
    }

    private suspend fun <T> get(url: String, parse: (String) -> T?): AiResult<T> = withRetry {
        val request = Request.Builder().url(url).header("xi-api-key", apiKey).get().build()
        when (val r = http.fetch(request)) {
            is AiResult.Failure -> r
            is AiResult.Success -> {
                val (code, body) = r.value
                if (code !in 200..299) httpFailure(code, body)
                else runCatching { parse(body) }.getOrNull()?.let { AiResult.Success(it) }
                    ?: AiResult.Failure(FailureKind.UNKNOWN, "ElevenLabs returned an unreadable list")
            }
        }
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
