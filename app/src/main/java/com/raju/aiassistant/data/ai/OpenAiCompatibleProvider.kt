package com.raju.aiassistant.data.ai

import com.raju.aiassistant.domain.ai.AiModel
import com.raju.aiassistant.domain.ai.AiProvider
import com.raju.aiassistant.domain.ai.AiResult
import com.raju.aiassistant.domain.ai.FailureKind
import com.raju.aiassistant.domain.ai.ProviderId
import com.raju.aiassistant.domain.ai.Turn
import com.raju.aiassistant.domain.ai.TurnRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Adapter for OpenAI-compatible chat APIs. Used for xAI Grok today; a future OpenAI provider
 * only needs a new [ProviderId] and base URL.
 */
class OpenAiCompatibleProvider(
    override val id: ProviderId,
    private val http: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String,
) : AiProvider {

    override suspend fun listModels(): AiResult<List<AiModel>> = withRetry {
        val request = Request.Builder().url("$baseUrl/models").header("Authorization", "Bearer $apiKey").get().build()
        when (val r = http.fetch(request)) {
            is AiResult.Failure -> r
            is AiResult.Success -> {
                val (code, body) = r.value
                if (code !in 200..299) httpFailure(code, body) else {
                    val root = runCatching { JsonFormat.parseToJsonElement(body) as JsonObject }.getOrNull()
                    if (root == null) AiResult.Failure(FailureKind.UNKNOWN, "Unreadable model list")
                    else AiResult.Success(
                        (root["data"] as? JsonArray).orEmpty().mapNotNull { el ->
                            ((el as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.let { AiModel(it, it) }
                        }.sortedBy { it.id }
                    )
                }
            }
        }
    }

    override suspend fun chat(model: String, systemPrompt: String, turns: List<Turn>): AiResult<String> = withRetry {
        val payload = buildJsonObject {
            put("model", model)
            put("temperature", 0.7)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", systemPrompt) }
                turns.forEach { t ->
                    addJsonObject {
                        put("role", if (t.role == TurnRole.ASSISTANT) "assistant" else "user")
                        put("content", t.text)
                    }
                }
            }
        }
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody())
            .build()
        when (val r = http.fetch(request)) {
            is AiResult.Failure -> r
            is AiResult.Success -> {
                val (code, body) = r.value
                if (code !in 200..299) httpFailure(code, body) else parseReply(body)
            }
        }
    }

    private fun parseReply(body: String): AiResult<String> {
        val root = runCatching { JsonFormat.parseToJsonElement(body) as JsonObject }.getOrNull()
            ?: return AiResult.Failure(FailureKind.UNKNOWN, "Unreadable response")
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val text = ((choice?.get("message") as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull
        return if (!text.isNullOrBlank()) AiResult.Success(text.trim())
        else AiResult.Failure(FailureKind.UNKNOWN, "Empty response")
    }
}

const val XAI_BASE_URL = "https://api.x.ai/v1"
