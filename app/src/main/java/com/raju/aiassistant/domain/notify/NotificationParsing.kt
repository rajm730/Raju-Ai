package com.raju.aiassistant.domain.notify

/** Raw fields copied out of a posted notification (no Android types, so parsing is unit-testable). */
data class NotificationFields(
    val packageName: String,
    val key: String,
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val conversationTitle: String? = null,
    val isGroupConversation: Boolean = false,
    /** MessagingStyle messages, oldest first: (sender name, text). */
    val messages: List<Pair<String?, String?>> = emptyList(),
    /** Notification.category, e.g. "msg" or "call". */
    val category: String? = null,
    val isGroupSummary: Boolean = false,
    val isOngoing: Boolean = false,
)

/** A message or call worth announcing. [preview] is null when the notification hides the content. */
data class IncomingItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val sender: String?,
    val group: String?,
    val preview: String?,
    val isCall: Boolean = false,
    val count: Int = 1,
) {
    /** Same app + same person/group → merged into one announcement. */
    val threadId: String get() = "$packageName|${group.orEmpty()}|${sender.orEmpty()}"
}

private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")
private val HIDDEN_PREVIEW = Regex(
    "^(\\d+\\s+)?new messages?$|contents? hidden|message hidden|^new message$|checking for new messages|" +
        "^\\d+\\s+messages?\\s+from\\s+\\d+\\s+chats?$|^you have \\d+ new|^sensitive notification content hidden$|" +
        "^(\\d+\\s+)?(नया|नए)\\s+संदेश(ों)?$|संदेश\\s+छिपा|सामग्री\\s+छिपी|^\\d+\\s+संदेश(ों)?$",
    RegexOption.IGNORE_CASE,
)

private fun clean(s: String?): String? = s?.replace(INVISIBLE, "")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Extracts who sent what. Uses the latest MessagingStyle message when present (so a group's name
 * isn't mistaken for the sender), falls back to title/text, and treats placeholder text such as
 * "2 new messages" as a hidden preview. Returns null for summaries and non-call ongoing notifications.
 */
fun parseNotification(f: NotificationFields, appLabel: String): IncomingItem? {
    if (f.isGroupSummary) return null
    val isCall = f.category.equals("call", ignoreCase = true)
    if (f.isOngoing && !isCall) return null

    val conversation = clean(f.conversationTitle)
    val latest = f.messages.lastOrNull { !clean(it.second).isNullOrEmpty() }
    var sender = clean(latest?.first) ?: clean(f.title)
    var preview = clean(latest?.second) ?: clean(f.bigText) ?: clean(f.text)
    var group = if (f.isGroupConversation) conversation else null

    // Older WhatsApp style for groups: title "Family: Mom" or "Mom @ Family".
    if (group == null && latest == null) {
        val t = clean(f.title)
        if (t != null) {
            Regex("^(.+?):\\s+(.+)$").matchEntire(t)?.let { group = it.groupValues[1]; sender = it.groupValues[2] }
                ?: Regex("^(.+?)\\s+@\\s+(.+)$").matchEntire(t)?.let { sender = it.groupValues[1]; group = it.groupValues[2] }
        }
    }
    if (group != null && sender == group) sender = null
    if (preview != null && HIDDEN_PREVIEW.containsMatchIn(preview)) preview = null
    if (sender == null && preview == null && group == null) return null

    return IncomingItem(f.key, f.packageName, appLabel, sender, group, if (isCall) null else preview, isCall)
}

/** "Mom", "Mom (Family group)", or "kisi" when unknown. */
fun whoText(item: IncomingItem): String = when {
    item.sender != null && item.group != null -> "${item.sender} (${item.group} group)"
    item.sender != null -> item.sender
    item.group != null -> "${item.group} group"
    else -> "kisi"
}

/** Spoken first, before any content: asks permission to read. */
fun askToReadText(item: IncomingItem): String =
    if (item.count > 1) "${item.appLabel} par ${whoText(item)} ke ${item.count} messages aaye hain. Kya main padhkar sunaun?"
    else "${item.appLabel} par ${whoText(item)} ka message aaya hai. Kya main padhkar sunaun?"

/** When the notification hides the content, only the source can be announced. */
fun hiddenPreviewText(item: IncomingItem): String =
    "${item.appLabel} par ${whoText(item)} ka message aaya hai. Preview chhupa hua hai, isliye main use padh nahi sakti."

/** Read only after the user said yes. Long previews are shortened. */
fun readPreviewText(item: IncomingItem): String {
    val p = item.preview.orEmpty().let { if (it.length > 300) it.take(300) + "…" else it }
    return "${whoText(item).replaceFirstChar { it.uppercase() }} ne likha: $p"
}

/** Best-effort announcement for app (VoIP) calls taken from the call notification. */
fun voipCallText(item: IncomingItem): String = "${item.appLabel} par ${whoText(item)} ka call aa raha hai."
