package com.raju.aiassistant.domain.assistant

private val URL = Regex("""https?://\S+""")
private val CITATION = Regex("""\[\d+]""")
private val MARKDOWN = Regex("""[*_`#>|]+""")
private val BULLET = Regex("""(?m)^\s*[-•]\s+""")
// Emoji and pictographs (incl. the ✅ ❌ ⚠️ markers used in app notes) are skipped, not read out.
private val EMOJI = Regex("""[\x{1F000}-\x{1FAFF}\x{2600}-\x{27BF}\x{2B00}-\x{2BFF}\x{FE0F}\x{200D}\x{20E3}]""")

/** Text suitable for TTS: no markdown, links or citation markers. */
fun speakableText(text: String): String = text
    .replace(URL, "")
    .replace(CITATION, "")
    .replace(BULLET, "")
    .replace(MARKDOWN, "")
    .replace(EMOJI, "")
    .replace(Regex("""[ \t]+"""), " ")
    .replace(Regex("""\n{2,}"""), "\n")
    .trim()

/**
 * Splits long text into chunks of at most [maxChars], preferring sentence boundaries (incl. Hindi "।").
 * The first chunk is kept to one short sentence group ([firstMaxChars]) so speech starts quickly.
 */
fun chunkForSpeech(text: String, maxChars: Int = 400, firstMaxChars: Int = 160): List<String> {
    val sentences = Regex("""(?<=[.!?।\n])\s+""").split(text).map { it.trim() }.filter { it.isNotEmpty() }
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    fun limit() = if (chunks.isEmpty()) firstMaxChars.coerceAtMost(maxChars) else maxChars
    fun flush() {
        if (current.isNotEmpty()) { chunks += current.toString(); current.clear() }
    }
    for (s in sentences) {
        var sentence = s
        while (sentence.length > limit()) { // very long sentence: hard split on a space
            flush()
            val cut = sentence.lastIndexOf(' ', limit()).takeIf { it > 0 } ?: limit()
            chunks += sentence.substring(0, cut).trim()
            sentence = sentence.substring(cut).trim()
        }
        if (current.isNotEmpty() && current.length + sentence.length + 1 > limit()) flush()
        if (current.isNotEmpty()) current.append(' ')
        current.append(sentence)
    }
    flush()
    return chunks.filter { it.isNotBlank() }
}

/** Only plain http(s) links with a host are shown or opened. */
fun isSafeWebUrl(url: String): Boolean {
    val u = url.trim()
    if (!(u.startsWith("https://", true) || u.startsWith("http://", true))) return false
    val host = u.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    return host.isNotEmpty() && !host.contains('@') && host.none { it.isWhitespace() }
}

private val SECRET_PATTERNS = listOf(
    Regex("""AIza[0-9A-Za-z_\-]{20,}"""),          // Google API keys
    Regex("""\b(sk|xai|tvly)-[0-9A-Za-z_\-]{10,}"""), // OpenAI / xAI / Tavily style keys
    Regex("""\bgsk_[0-9A-Za-z]{10,}"""),             // Groq keys
    Regex("""\b(?:\d[ -]?){13,19}\b"""),             // card-like digit runs
    Regex("""(?i)\b(password|passcode|passwd|pin|otp|cvv|upi pin)\b"""),
    Regex("""(पासवर्ड|पिन|ओटीपी)"""),
)

/** True if text looks like it contains a secret that must never be stored as a memory. */
fun looksSensitive(text: String): Boolean = SECRET_PATTERNS.any { it.containsMatchIn(text) }
