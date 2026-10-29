package com.raju.aiassistant.domain.assistant

/**
 * Prevents announcing the same notification twice (apps often re-post/update notifications).
 * Keeps a small fixed-size memory of fingerprints; message text itself is not stored, only a hash.
 */
class AnnouncementDeduper(private val capacity: Int = 64, private val windowMs: Long = 10 * 60_000L) {
    private val seen = LinkedHashMap<Int, Long>()

    /** Returns true if this notification should be announced now. */
    @Synchronized
    fun shouldAnnounce(packageName: String, title: String, text: String, now: Long): Boolean {
        val fingerprint = (packageName + '\u0000' + title + '\u0000' + text).hashCode()
        seen.entries.removeAll { now - it.value > windowMs }
        if (seen.containsKey(fingerprint)) return false
        seen[fingerprint] = now
        while (seen.size > capacity) seen.remove(seen.keys.first())
        return true
    }
}

/** Builds the spoken announcement; previews are truncated so long messages are not read in full. */
fun announcementText(appLabel: String, sender: String?, preview: String?): String {
    val from = sender?.takeIf { it.isNotBlank() }?.let { "$appLabel par $it ka message" } ?: "$appLabel notification"
    val body = preview?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length > 120) it.take(120) + "…" else it }
    return if (body != null) "$from: $body" else from
}
