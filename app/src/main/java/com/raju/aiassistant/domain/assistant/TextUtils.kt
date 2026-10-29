package com.raju.aiassistant.domain.assistant

private val URL = Regex("""https?://\S+""")
private val CITATION = Regex("""\[\d+]""")
private val MARKDOWN = Regex("""[*_`#>|]+""")
private val BULLET = Regex("""(?m)^\s*[-•]\s+""")

/** Text suitable for TTS: no markdown, links or citation markers. */
fun speakableText(text: String): String = text
    .replace(URL, "")
    .replace(CITATION, "")
    .replace(BULLET, "")
    .replace(MARKDOWN, "")
    .replace(Regex("""[ \t]+"""), " ")
    .replace(Regex("""\n{2,}"""), "\n")
    .trim()

/** Splits long text into chunks of at most [maxChars], preferring sentence boundaries (incl. Hindi "।"). */
fun chunkForSpeech(text: String, maxChars: Int = 450): List<String> {
    val sentences = Regex("""(?<=[.!?।\n])\s+""").split(text).map { it.trim() }.filter { it.isNotEmpty() }
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    for (s in sentences) {
        var sentence = s
        while (sentence.length > maxChars) { // very long sentence: hard split on a space
            val cut = sentence.lastIndexOf(' ', maxChars).takeIf { it > 0 } ?: maxChars
            if (current.isNotEmpty()) { chunks += current.toString(); current.clear() }
            chunks += sentence.substring(0, cut).trim()
            sentence = sentence.substring(cut).trim()
        }
        if (current.length + sentence.length + 1 > maxChars && current.isNotEmpty()) {
            chunks += current.toString(); current.clear()
        }
        if (current.isNotEmpty()) current.append(' ')
        current.append(sentence)
    }
    if (current.isNotEmpty()) chunks += current.toString()
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
