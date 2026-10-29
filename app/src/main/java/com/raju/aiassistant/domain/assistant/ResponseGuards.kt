package com.raju.aiassistant.domain.assistant

/** Result of [collapseRepetition]. */
data class RepetitionCheck(val text: String, val degenerate: Boolean)

/**
 * Detects a model stuck in a loop — the same phrase repeated many times in a row — and keeps a single
 * copy. Normal emphasis ("bahut bahut dhanyavaad", "haan haan") is left alone: a phrase of 2+ words
 * must repeat 4+ times consecutively, or a single word 6+ times.
 */
fun collapseRepetition(text: String): RepetitionCheck {
    val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.size < 6) return RepetitionCheck(text, false)
    val out = ArrayList<String>(tokens.size)
    var i = 0
    var degenerate = false
    fun norm(t: String) = t.lowercase().trim { !it.isLetterOrDigit() }
    while (i < tokens.size) {
        var collapsed = false
        for (len in 1..12) {
            if (i + len * 2 > tokens.size) break
            val unit = (0 until len).map { norm(tokens[i + it]) }
            if (unit.all { it.isEmpty() }) continue
            var reps = 1
            while (i + (reps + 1) * len <= tokens.size && (0 until len).all { norm(tokens[i + reps * len + it]) == unit[it] }) reps++
            val threshold = if (len == 1) 6 else 4
            if (reps >= threshold) {
                out.addAll(tokens.subList(i, i + len))
                i += reps * len
                degenerate = true
                collapsed = true
                break
            }
        }
        if (!collapsed) out += tokens[i++]
    }
    return if (degenerate) RepetitionCheck(out.joinToString(" "), true) else RepetitionCheck(text, false)
}

/** Ignores the same message sent again within [windowMs] (double tap / repeated voice result). */
class SubmitGuard(private val windowMs: Long = 1_500) {
    private var lastText: String? = null
    private var lastAt = Long.MIN_VALUE

    @Synchronized
    fun accept(text: String, now: Long): Boolean {
        val key = text.trim().lowercase()
        val duplicate = key == lastText && now - lastAt < windowMs
        lastText = key
        lastAt = now
        return !duplicate
    }
}

/** Outcome of validating a memory before it is saved. */
sealed interface MemoryCheck {
    data class Ok(val text: String) : MemoryCheck
    data class Rejected(val reason: String) : MemoryCheck
}

const val MAX_MEMORY_CHARS = 300
const val MAX_MEMORIES = 200

fun validateMemory(text: String, existing: List<String>): MemoryCheck {
    val t = text.trim().replace(Regex("\\s+"), " ")
    return when {
        t.isEmpty() -> MemoryCheck.Rejected("Memory is empty.")
        t.length > MAX_MEMORY_CHARS -> MemoryCheck.Rejected("Memory is too long (max $MAX_MEMORY_CHARS characters).")
        looksSensitive(t) -> MemoryCheck.Rejected("Not saved: this looks like a password, PIN, OTP, card number or key.")
        existing.any { it.trim().equals(t, ignoreCase = true) } -> MemoryCheck.Rejected("Already remembered.")
        existing.size >= MAX_MEMORIES -> MemoryCheck.Rejected("Memory is full ($MAX_MEMORIES items). Delete some in Tools → Memory.")
        else -> MemoryCheck.Ok(t)
    }
}
