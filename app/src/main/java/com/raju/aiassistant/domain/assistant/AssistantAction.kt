package com.raju.aiassistant.domain.assistant

import com.raju.aiassistant.domain.model.MusicService
import com.raju.aiassistant.domain.tools.MediaCommand
import com.raju.aiassistant.domain.tools.TravelMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt

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
    /** [topic] is "news" for current events (adds publish dates), otherwise null for a general search. */
    data class SearchWeb(val query: String, val topic: String? = null) : AssistantAction {
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
    data class SetTimer(val seconds: Int, val note: String) : AssistantAction {
        override val label get() = "Timer: ${seconds}s"
    }
    data class SetAlarm(val hour: Int, val minute: Int, val note: String) : AssistantAction {
        override val label get() = "Alarm: %02d:%02d".format(hour, minute)
    }
    data class Flashlight(val on: Boolean) : AssistantAction {
        override val label get() = "Flashlight " + if (on) "on" else "off"
    }
    data class Volume(val percent: Int) : AssistantAction {
        override val label get() = "Volume: $percent%"
    }
    data class Media(val command: MediaCommand) : AssistantAction {
        override val label get() = "Media: ${command.name.lowercase()}"
    }
    data class Sms(val contact: String?, val number: String?, val text: String) : AssistantAction {
        override val label get() = "SMS to ${contact ?: number}"
    }
    /** [start] is local phone time; the calendar app opens pre-filled and the user saves it. */
    data class AddEvent(val title: String, val start: LocalDateTime, val durationMinutes: Int, val location: String?) : AssistantAction {
        override val label get() = "Calendar: $title @ $start"
    }
    data class Navigate(val destination: String, val mode: TravelMode) : AssistantAction {
        override val label get() = "Directions to $destination"
    }
    data class CopyText(val text: String) : AssistantAction {
        override val label get() = "Copy to clipboard"
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
    // Models send numbers as 5, 5.0 or "5"; all are accepted.
    fun num(key: String): Double? = str(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }
    fun int(key: String): Int? = num(key)?.roundToInt()

    return when (str("type")?.lowercase()) {
        "call" -> if (str("contact") == null && str("number") == null) null else AssistantAction.Call(str("contact"), str("number"))
        "open_app" -> str("name")?.let { AssistantAction.OpenApp(it) }
        "search_web" -> str("query")?.let { AssistantAction.SearchWeb(it, str("topic")?.lowercase()?.takeIf { t -> t == "news" }) }
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
        "set_timer" -> {
            val seconds = num("seconds")?.roundToInt()
                ?: (num("minutes")?.let { it * 60 } ?: num("hours")?.let { it * 3600 })?.roundToInt()
            if (seconds == null || seconds !in 1..86_400) null else AssistantAction.SetTimer(seconds, str("label").orEmpty())
        }
        "set_alarm" -> {
            val h = int("hour")
            val m = int("minute") ?: 0
            if (h == null || h !in 0..23 || m !in 0..59) null else AssistantAction.SetAlarm(h, m, str("label").orEmpty())
        }
        "flashlight", "torch" -> AssistantAction.Flashlight((str("state")?.lowercase() ?: "on") !in setOf("off", "false", "0", "band"))
        "volume" -> int("percent")?.takeIf { it in 0..100 }?.let { AssistantAction.Volume(it) }
        "media" -> when (str("command")?.lowercase()) {
            "play" -> MediaCommand.PLAY
            "pause", "stop" -> MediaCommand.PAUSE
            "next" -> MediaCommand.NEXT
            "previous", "prev" -> MediaCommand.PREVIOUS
            else -> null
        }?.let { AssistantAction.Media(it) }
        "sms" -> if (str("text") == null || (str("contact") == null && str("number") == null)) null
                 else AssistantAction.Sms(str("contact"), str("number"), str("text")!!)
        "add_event", "reminder" -> {
            val title = str("title") ?: str("text")
            val start = str("start")?.let { parseLocalDateTime(it) }
            val minutes = int("duration_minutes")?.takeIf { it in 1..1440 } ?: 30
            if (title == null || start == null) null else AssistantAction.AddEvent(title, start, minutes, str("location"))
        }
        "navigate", "directions" -> str("destination")?.let { d ->
            val mode = when (str("mode")?.lowercase()) {
                "walk", "walking" -> TravelMode.WALK
                "bike", "bicycle", "two_wheeler" -> TravelMode.BIKE
                "transit", "bus", "metro", "train" -> TravelMode.TRANSIT
                else -> TravelMode.DRIVE
            }
            AssistantAction.Navigate(d, mode)
        }
        "copy", "copy_text" -> str("text")?.let { AssistantAction.CopyText(it) }
        else -> null
    }
}

/** Accepts "2026-10-30T17:00", "2026-10-30T17:00:00" or "2026-10-30 17:00" (local time). */
internal fun parseLocalDateTime(value: String): LocalDateTime? = try {
    LocalDateTime.parse(value.trim().replace(' ', 'T').removeSuffix("Z").let { if (it.length == 16) "$it:00" else it })
} catch (e: DateTimeParseException) {
    null
}
