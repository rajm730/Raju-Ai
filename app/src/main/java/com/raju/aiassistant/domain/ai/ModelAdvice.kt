package com.raju.aiassistant.domain.ai

/**
 * Guidance about provider models for a Hindi/Hinglish/English assistant. Model lists come live from
 * the provider; this only ranks and annotates them (checked against provider model cards, Oct 2026).
 */
object ModelAdvice {
    /** Models that are not general chat models (speech, safety classifiers, embeddings, agents). */
    private val NOT_CHAT = listOf("image", "vision", "embed", "tts", "audio", "whisper", "guard", "playai", "compound", "orpheus")

    /** Language-specialised models that are poor at Hindi (e.g. ALLaM is an Arabic–English model with a ~4K context). */
    private val NOT_FOR_HINDI = listOf("allam", "saba")

    /** Preferred defaults, best first. Matched by substring. */
    private val PREFERRED = mapOf(
        ProviderId.GEMINI to listOf("gemini-2.5-flash", "gemini-2.0-flash", "flash"),
        ProviderId.GROQ to listOf("llama-3.3-70b", "gpt-oss-120b", "llama-4", "qwen3", "llama-3.1-8b", "llama"),
    )

    fun isChatModel(id: String) = NOT_CHAT.none { id.contains(it, ignoreCase = true) }

    /** Non-null when the model is a poor fit for this assistant; shown next to the model in Settings and Chat. */
    fun warning(id: String?): String? = when {
        id == null -> null
        NOT_FOR_HINDI.any { id.contains(it, ignoreCase = true) } ->
            "This model is built for Arabic–English and has a small context. Hindi replies may be wrong or repetitive — choose a recommended model."
        !isChatModel(id) -> "This is not a chat model. Choose a recommended model."
        else -> null
    }

    fun isRecommended(provider: ProviderId, id: String): Boolean =
        warning(id) == null && PREFERRED[provider].orEmpty().take(4).any { id.contains(it, ignoreCase = true) } &&
            !(provider == ProviderId.GEMINI && id.contains("lite", ignoreCase = true))

    /** Recommended models first, unsuitable ones last, the rest alphabetical. */
    fun rank(provider: ProviderId, models: List<AiModel>): List<AiModel> = models.sortedWith(
        compareBy<AiModel>(
            { if (isRecommended(provider, it.id)) 0 else if (warning(it.id) == null) 1 else 2 },
            { m -> PREFERRED[provider].orEmpty().indexOfFirst { m.id.contains(it, ignoreCase = true) }.let { if (it < 0) Int.MAX_VALUE else it } },
            { it.id },
        )
    )

    /** Rough history budget in characters, so small-context models are not overflowed. */
    fun historyCharBudget(id: String): Int = if (NOT_FOR_HINDI.any { id.contains(it, ignoreCase = true) }) 6_000 else 24_000
}

/** Picks a sensible default when the user has not chosen a model yet. */
fun pickDefaultModel(provider: ProviderId, models: List<AiModel>): AiModel? =
    ModelAdvice.rank(provider, models).firstOrNull { ModelAdvice.warning(it.id) == null } ?: models.firstOrNull()
