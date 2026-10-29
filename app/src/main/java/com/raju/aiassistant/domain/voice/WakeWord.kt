package com.raju.aiassistant.domain.voice

/** How speech recognition writes "Seema" (Hindi and Latin script, common mishearings). */
private val WAKE_WORDS = listOf("seema", "sima", "shima", "seemaa", "sema", "सीमा", "सिमा", "शीमा", "सीमां", "सीमाजी", "सीमा जी")
private val LEADING_FILLERS = Regex("^(hey|hi|hello|ok|okay|oye|arre|are|suno|हे|हाय|हेलो|अरे|सुनो|ओए)\\s+", RegexOption.IGNORE_CASE)

/**
 * In sleep mode Seema only reacts when called by name. Returns what was said after "Seema"
 * ("" if only her name was said), or null if her name was not in [heard] at all.
 * Examples: "Seema, time kya hua?" → "time kya hua?"; "hey सीमा" → ""; "TV ki awaaz" → null.
 */
fun afterWakeWord(heard: String): String? {
    val text = heard.trim()
    val lower = text.lowercase()
    var best: IntRange? = null
    for (w in WAKE_WORDS) {
        val m = Regex("(^|[^\\p{L}\\p{M}])" + Regex.escape(w) + "(?=$|[^\\p{L}\\p{M}])").find(lower) ?: continue
        val range = (m.range.first + m.groupValues[1].length)..m.range.last
        if (best == null || range.first < best.first) best = range
    }
    val hit = best ?: return null
    // The name should open the sentence (allowing "hey"/"arre" before it), not be buried in other talk.
    val before = text.substring(0, hit.first).trim().replace(LEADING_FILLERS, "").trim().trimEnd(',', '!', '.')
    if (before.split(Regex("\\s+")).count { it.isNotBlank() } > 1) return null
    return text.substring(hit.last + 1).trim().trimStart(',', '!', '.', '?', '।', ' ').trim()
}
