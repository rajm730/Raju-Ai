package com.raju.aiassistant.data.search

import com.raju.aiassistant.data.ai.JsonFormat
import com.raju.aiassistant.data.ai.fetch
import com.raju.aiassistant.data.ai.httpFailure
import com.raju.aiassistant.data.ai.toRequestBody
import com.raju.aiassistant.data.ai.withRetry
import com.raju.aiassistant.domain.ai.AiResult
import com.raju.aiassistant.domain.ai.FailureKind
import com.raju.aiassistant.domain.assistant.isSafeWebUrl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

data class SearchResult(val title: String, val url: String, val snippet: String)
data class SearchResponse(val answer: String?, val results: List<SearchResult>)

interface WebSearch {
    suspend fun search(query: String, maxResults: Int = 5): AiResult<SearchResponse>
}

/** Real web search through the Tavily API. Results are returned as-is; nothing is fabricated. */
class TavilyClient(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.tavily.com",
) : WebSearch {
    override suspend fun search(query: String, maxResults: Int): AiResult<SearchResponse> = withRetry {
        val payload = buildJsonObject {
            put("query", query.take(400))
            put("max_results", maxResults)
            put("include_answer", true)
            put("search_depth", "basic")
        }
        val request = Request.Builder()
            .url("$baseUrl/search")
            .header("Authorization", "Bearer $apiKey")
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

    private fun parse(body: String): AiResult<SearchResponse> {
        val root = runCatching { JsonFormat.parseToJsonElement(body) as JsonObject }.getOrNull()
            ?: return AiResult.Failure(FailureKind.UNKNOWN, "Unreadable search response")
        val results = (root["results"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val url = (o["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (!isSafeWebUrl(url)) return@mapNotNull null
            SearchResult(
                title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: url,
                url = url,
                snippet = (o["content"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
        }
        val answer = (root["answer"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return AiResult.Success(SearchResponse(answer, results))
    }
}
