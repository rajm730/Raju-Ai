package com.raju.aiassistant.domain.notify

/**
 * What to say for an incoming SIM call. The caller's name is used only when it was found in the
 * user's Contacts; the number is spoken only if the user enabled that; otherwise no identity is
 * invented ("unknown number" / "ek call").
 */
fun callAnnouncementText(contactName: String?, number: String?, speakNumber: Boolean): String {
    val digits = number?.filter { it.isDigit() || it == '+' }.orEmpty()
    return when {
        !contactName.isNullOrBlank() -> "$contactName ka call aa raha hai."
        digits.length >= 3 && speakNumber -> "Number ${digits.toList().joinToString(" ")} se call aa raha hai."
        digits.isEmpty() -> "Ek call aa raha hai. Number chhupa hua hai."
        else -> "Unknown number se call aa raha hai."
    }
}

/** One announcement per call: the same number ringing again within [windowMs] is ignored. */
class CallDeduper(private val windowMs: Long = 60_000) {
    private val recent = HashMap<String, Long>()

    @Synchronized
    fun shouldAnnounce(number: String?, now: Long): Boolean {
        val key = number?.filter { it.isDigit() }?.takeLast(10).orEmpty()
        if (key.isEmpty()) return true // hidden number: every ring is announced
        recent.entries.removeAll { now - it.value > windowMs }
        if (recent.containsKey(key)) return false
        recent[key] = now
        return true
    }
}
