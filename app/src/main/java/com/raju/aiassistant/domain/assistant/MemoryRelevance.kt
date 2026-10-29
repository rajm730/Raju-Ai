package com.raju.aiassistant.domain.assistant

private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")

/** Very common Hindi/Hinglish/English words that say nothing about the topic. */
private val STOP_WORDS = setOf(
    "the", "a", "an", "is", "are", "my", "me", "i", "to", "of", "and", "in", "on", "for", "what", "how", "please",
    "ka", "ki", "ke", "ko", "hai", "hain", "tha", "mera", "meri", "mere", "kya", "kaise", "kab", "aur", "se", "me", "mein", "batao", "bolo", "karo",
    "का", "की", "के", "को", "है", "हैं", "था", "मेरा", "मेरी", "मेरे", "क्या", "कैसे", "कब", "और", "से", "में", "बताओ", "बोलो", "करो",
)

private fun keywords(text: String): Set<String> =
    WORD.findAll(text.lowercase()).map { it.value }.filter { it.length >= 3 && it !in STOP_WORDS }.toSet()

/**
 * Smart memory: with only a few memories all of them are used; with many, the ones that share words
 * with what the user just said come first, the rest are filled with the newest. Runs on the phone,
 * no data is sent anywhere for this. [memories] are newest first.
 */
fun relevantMemories(memories: List<String>, query: String, limit: Int = 12): List<String> {
    if (memories.size <= limit) return memories
    val q = keywords(query)
    val scored = memories.mapIndexed { i, m ->
        val words = keywords(m)
        // Partial matches count too ("birthday" ~ "birthdays", "मम्मी" ~ "मम्मीजी").
        val score = words.count { w -> q.any { it == w || (it.length >= 4 && (w.startsWith(it) || it.startsWith(w))) } }
        Triple(i, m, score)
    }
    val relevant = scored.filter { it.third > 0 }.sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }.thenBy { it.first }).map { it.second }
    return (relevant + memories).distinct().take(limit)
}
