package com.raju.aiassistant.data.ai

import com.raju.aiassistant.domain.ai.AiProvider
import com.raju.aiassistant.domain.ai.ProviderId
import okhttp3.OkHttpClient

class AiProviderFactory(private val http: OkHttpClient) {
    fun create(id: ProviderId, apiKey: String): AiProvider = when (id) {
        ProviderId.GEMINI -> GeminiProvider(http, apiKey)
        ProviderId.GROK -> OpenAiCompatibleProvider(ProviderId.GROK, http, apiKey, XAI_BASE_URL)
    }
}
