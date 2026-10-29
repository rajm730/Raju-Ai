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
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/** Google Gemini via the Generative Language REST API (key sent in a header, never in the URL). */
class GeminiProvider(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) : AiProvider {
    override val id = ProviderId.GEMINI

    override suspend fun listModels(): AiResult<List<AiModel>> = withRetry {
        val request = Request.Builder()
            .url("$baseUrl/models?pageSize=200")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        when (val r = http.fetch(request)) {
            is AiResult.Failure -> r
            is AiResult.Success -> {
                val (code, body) = r.value
                if (code !in 200..299) httpFailure(code, body) else parseModels(body)
            }
        }
    }

    override suspend fun chat(model: String, systemPrompt: String, turns: List<Turn>): AiResult<String> = withRetry {
        val payload = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", systemPrompt) } } }
            putJsonArray("contents") {
                turns.forEach { t ->
                    addJsonObject {
                        put("role", if (t.role == TurnRole.ASSISTANT) "model" else "user")
                        putJsonArray("parts") { addJsonObject { put("text", t.text) } }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.7)
                put("maxOutputTokens", 4096)
            }
        }
        val request = Request.Builder()
            .url("$baseUrl/models/${model.removePrefix("models/")}:generateContent")
            .header("x-goog-api-key", apiKey)
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

    private fun parseModels(body: String): AiResult<List<AiModel>> {
        val root = runCatching { JsonFormat.parseToJsonElement(body) as JsonObject }.getOrNull()
            ?: return AiResult.Failure(FailureKind.UNKNOWN, "Unreadable model list")
        val models = (root["models"] as? JsonArray).orEmpty().mapNotNull { el ->
            val m = el as? JsonObject ?: return@mapNotNull null
            val methods = (m["supportedGenerationMethods"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if ("generateContent" !in methods) return@mapNotNull null
            val name = (m["name"] as? JsonPrimitive)?.contentOrNull?.removePrefix("models/") ?: return@mapNotNull null
            AiModel(name, (m["displayName"] as? JsonPrimitive)?.contentOrNull ?: name)
        }
        return AiResult.Success(models)
    }

    private fun parseReply(body: String): AiResult<String> {
        val root = runCatching { JsonFormat.parseToJsonElement(body) as JsonObject }.getOrNull()
            ?: return AiResult.Failure(FailureKind.UNKNOWN, "Unreadable response")
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        val parts = ((candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        val text = parts.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }.joinToString("")
        val finish = (candidate?.get("finishReason") as? JsonPrimitive)?.contentOrNull
        if (text.isNotBlank()) return AiResult.Success(text.trim() + if (finish == "MAX_TOKENS") TRUNCATED_NOTE else "")
        val blockReason = ((root["promptFeedback"] as? JsonObject)?.get("blockReason") as? JsonPrimitive)?.contentOrNull
        return if (blockReason != null || finish == "SAFETY") {
            AiResult.Failure(FailureKind.BLOCKED, FailureKind.BLOCKED.userMessage)
        } else {
            if (finish == "MAX_TOKENS") {
                AiResult.Failure(FailureKind.UNKNOWN, "The model used its whole output limit before writing a reply. Ask a shorter question or pick a \"flash\" model in Settings.")
            } else {
                AiResult.Failure(FailureKind.UNKNOWN, "Empty response" + (finish?.let { " ($it)" } ?: ""))
            }
        }
    }
}
