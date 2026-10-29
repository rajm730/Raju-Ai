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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Cartesia Sonic text-to-speech (https://cartesia.ai). Returns 24 kHz 16-bit WAV. */
class CartesiaClient(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.cartesia.ai",
) {
    suspend fun synthesize(text: String, voiceId: String, modelId: String, language: String, speed: Float, emotion: String?): AiResult<ByteArray> = withRetry {
        val payload = buildJsonObject {
            put("model_id", modelId)
            put("transcript", text)
            putJsonObject("voice") { put("id", voiceId) }
            putJsonObject("output_format") {
                put("container", "wav")
                put("encoding", "pcm_s16le")
                put("sample_rate", 24000)
            }
            put("language", language)
            // Speed, volume and emotion apply to Sonic 3 and newer; older models ignore them.
            putJsonObject("generation_config") {
                put("speed", Math.round(speed.coerceIn(0.6f, 1.5f) * 100) / 100.0)
                if (!emotion.isNullOrBlank()) put("emotion", emotion)
            }
        }
        val request = request("$baseUrl/tts/bytes").header("Accept", "audio/wav").post(payload.toRequestBody()).build()
        when (val r = http.fetchBytes(request)) {
            is AiResult.Failure -> r
            is AiResult.Success -> {
                val (code, body) = r.value
                if (code !in 200..299) httpFailure(code, String(body)) else AiResult.Success(body)
            }
        }
    }

    /** Female ("feminine") voices, a few pages deep so Hindi voices are found too. */
    suspend fun listVoices(maxPages: Int = 4): AiResult<List<CloudVoice>> {
        val all = mutableListOf<CloudVoice>()
        var after: String? = null
        repeat(maxPages) {
            val url = "$baseUrl/voices".toHttpUrl().newBuilder()
                .addQueryParameter("limit", "100")
                .addQueryParameter("gender", "feminine")
                .apply { after?.let { addQueryParameter("starting_after", it) } }
                .build()
            val page = withRetry {
                when (val r = http.fetch(request(url.toString()).get().build())) {
                    is AiResult.Failure -> r
                    is AiResult.Success -> {
                        val (code, body) = r.value
                        if (code !in 200..299) httpFailure(code, body)
                        else parsePage(body)?.let { AiResult.Success(it) } ?: AiResult.Failure(FailureKind.UNKNOWN, "Cartesia returned an unreadable voice list")
                    }
                }
            }
            when (page) {
                is AiResult.Failure -> return if (all.isEmpty()) page else AiResult.Success(all)
                is AiResult.Success -> {
                    all += page.value.first
                    if (!page.value.second || page.value.first.isEmpty()) return AiResult.Success(all)
                    after = page.value.first.last().id
                }
            }
        }
        return AiResult.Success(all)
    }

    /** (voices, hasMore). Accepts both the paged {"data":[...]} shape and a plain array. */
    private fun parsePage(body: String): Pair<List<CloudVoice>, Boolean>? {
        val root = runCatching { JsonFormat.parseToJsonElement(body) }.getOrNull() ?: return null
        val items = (root as? JsonArray) ?: ((root as? JsonObject)?.get("data") as? JsonArray) ?: return null
        val hasMore = ((root as? JsonObject)?.get("has_more") as? JsonPrimitive)?.booleanOrNull ?: false
        return items.mapNotNull { v ->
            val o = v as? JsonObject ?: return@mapNotNull null
            val language = o.str("language").orEmpty()
            CloudVoice(
                id = o.str("id") ?: return@mapNotNull null,
                name = o.str("name") ?: "Voice",
                detail = (o.str("tagline") ?: o.str("description")).orEmpty().take(60),
                female = o.str("gender")?.let { it == "feminine" || it == "female" } ?: true,
                hindi = language == "hi",
            )
        } to hasMore
    }

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $apiKey")
        .header("Cartesia-Version", API_VERSION)

    companion object {
        const val API_VERSION = "2026-08-14"
    }
}
