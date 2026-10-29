package com.raju.aiassistant.domain.assistant

import com.raju.aiassistant.domain.model.MusicService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class SettingsPage { WIFI, BLUETOOTH, DISPLAY, SOUND, APPS, NOTIFICATION_ACCESS, BATTERY, APP_PERMISSIONS, GENERAL }

/** A tool call the AI asked for. Executed only by the app, after any required confirmation. */
sealed interface AssistantAction {
    val label: String

    data class Call(val contact: String?, val number: String?) : AssistantAction {
        override val label get() = "Call ${contact ?: number}"
    }
    data class OpenApp(val name: String) : AssistantAction {
        override val label get() = "Open app: $name"
    }
    data class SearchWeb(val query: String) : AssistantAction {
        override val label get() = "Web search: $query"
    }
    data class PlayMusic(val query: String, val service: MusicService?) : AssistantAction {
        override val label get() = "Play music: $query"
    }
    data class OpenSettings(val page: SettingsPage) : AssistantAction {
        override val label get() = "Open settings: ${page.name.lowercase()}"
    }
    data object Battery : AssistantAction {
        override val label get() = "Battery status"
    }
    data class Remember(val text: String) : AssistantAction {
        override val label get() = "Remember: $text"
    }
    data class WhatsApp(val contact: String?, val text: String) : AssistantAction {
        override val label get() = "WhatsApp draft" + (contact?.let { " to $it" } ?: "")
    }
}

data class ParsedReply(val text: String, val actions: List<AssistantAction>)

private val ACTION_TAG = Regex("<action>(.*?)</action>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }
const val MAX_ACTIONS_PER_REPLY = 5

/** Splits an AI reply into display text and the actions it requested. Malformed actions are ignored. */
fun parseReply(raw: String): ParsedReply {
    val actions = ACTION_TAG.findAll(raw)
        .mapNotNull { parseAction(it.groupValues[1].trim()) }
        .take(MAX_ACTIONS_PER_REPLY)
        .toList()
    val text = raw.replace(ACTION_TAG, "").replace(Regex("\n{3,}"), "\n\n").trim()
    return ParsedReply(text, actions)
}

private fun parseAction(json: String): AssistantAction? {
    val obj: JsonObject = try {
        lenientJson.parseToJsonElement(json).jsonObject
    } catch (e: Exception) {
        return null
    }
    fun str(key: String) = obj[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }

    return when (str("type")?.lowercase()) {
        "call" -> if (str("contact") == null && str("number") == null) null else AssistantAction.Call(str("contact"), str("number"))
        "open_app" -> str("name")?.let { AssistantAction.OpenApp(it) }
        "search_web" -> str("query")?.let { AssistantAction.SearchWeb(it) }
        "play_music" -> str("query")?.let { q ->
            val service = when (str("service")?.lowercase()) {
                "spotify" -> MusicService.SPOTIFY
                "youtube_music", "youtube", "yt_music" -> MusicService.YOUTUBE_MUSIC
                else -> null
            }
            AssistantAction.PlayMusic(q, service)
        }
        "open_settings" -> {
            val page = SettingsPage.entries.firstOrNull { it.name.equals(str("page"), ignoreCase = true) } ?: SettingsPage.GENERAL
            AssistantAction.OpenSettings(page)
        }
        "battery" -> AssistantAction.Battery
        "remember" -> str("text")?.let { AssistantAction.Remember(it) }
        "whatsapp" -> str("text")?.let { AssistantAction.WhatsApp(str("contact"), it) }
        else -> null
    }
}
