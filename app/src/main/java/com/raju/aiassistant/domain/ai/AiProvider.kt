package com.raju.aiassistant.domain.ai

import com.raju.aiassistant.domain.model.ChatMessage

/** Identifies an AI backend. New providers (e.g. OpenAI) are added here plus an adapter. */
enum class ProviderId(val displayName: String) {
    GEMINI("Google Gemini"),
    GROK("xAI Grok"),
}

data class AiModel(val id: String, val displayName: String)

/** Outcome of a provider call. Errors are explicit so the UI never shows fake success. */
sealed interface AiResult<out T> {
    data class Success<T>(val value: T) : AiResult<T>
    data class Failure(val kind: FailureKind, val message: String) : AiResult<Nothing>
}

enum class FailureKind { NOT_CONFIGURED, INVALID_KEY, RATE_LIMITED, QUOTA_EXCEEDED, TIMEOUT, NETWORK, SERVER, UNKNOWN }

/**
 * Adapter contract every AI provider implements. Concrete Gemini / Grok adapters
 * arrive in Phase 3; Phase 1 only fixes the shape so the rest of the app can depend on it.
 */
interface AiProvider {
    val id: ProviderId

    /** Performs a real authenticated request and reports the genuine result. */
    suspend fun testConnection(): AiResult<Unit>

    /** Lists models actually available to the configured key. */
    suspend fun listModels(): AiResult<List<AiModel>>

    suspend fun chat(model: String, history: List<ChatMessage>): AiResult<String>
}
