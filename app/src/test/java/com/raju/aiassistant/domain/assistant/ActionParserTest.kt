package com.raju.aiassistant.domain.assistant

import com.raju.aiassistant.domain.tools.MediaCommand
import com.raju.aiassistant.domain.tools.TravelMode
import java.time.LocalDateTime
import com.raju.aiassistant.domain.model.MusicService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionParserTest {
    @Test
    fun extractsActionsAndCleansText() {
        val raw = """Theek hai, main Mom ko call lagati hoon.
<action>{"type":"call","contact":"Mom"}</action>
<action>{"type":"play_music","query":"Kesariya","service":"spotify"}</action>"""
        val parsed = parseReply(raw)
        assertEquals("Theek hai, main Mom ko call lagati hoon.", parsed.text)
        assertEquals(listOf(AssistantAction.Call("Mom", null), AssistantAction.PlayMusic("Kesariya", MusicService.SPOTIFY)), parsed.actions)
    }

    @Test
    fun ignoresMalformedAndUnknownActions() {
        val parsed = parseReply("""Hi <action>{not json}</action> <action>{"type":"format_phone"}</action> <action>{"type":"call"}</action>""")
        assertTrue(parsed.actions.isEmpty())
        assertEquals("Hi", parsed.text)
    }

    @Test
    fun parsesAllSupportedTypes() {
        val raw = listOf(
            """{"type":"open_app","name":"Camera"}""",
            """{"type":"search_web","query":"weather Delhi"}""",
            """{"type":"open_settings","page":"wifi"}""",
            """{"type":"battery"}""",
            """{"type":"remember","text":"My birthday is 5 May"}""",
        ).joinToString("") { "<action>$it</action>" } + """<action>{"type":"whatsapp","text":"Hello"}</action>"""
        val actions = parseReply(raw).actions
        assertEquals(MAX_ACTIONS_PER_REPLY, actions.size)
        assertEquals(AssistantAction.OpenSettings(SettingsPage.WIFI), actions[2])
        assertEquals(AssistantAction.Battery, actions[3])
    }

    @Test
    fun unknownSettingsPageFallsBackToGeneral() {
        assertEquals(AssistantAction.OpenSettings(SettingsPage.GENERAL), parseReply("""<action>{"type":"open_settings","page":"???"}</action>""").actions.single())
    }

    @Test
    fun parsesPersonalUseActions() {
        val raw = listOf(
            """{"type":"set_timer","minutes":2}""",
            """{"type":"set_alarm","hour":6,"minute":30,"label":"uthna"}""",
            """{"type":"flashlight","state":"off"}""",
            """{"type":"volume","percent":60}""",
            """{"type":"media","command":"next"}""",
        ).joinToString("") { "<action>$it</action>" }
        assertEquals(
            listOf(
                AssistantAction.SetTimer(120, ""),
                AssistantAction.SetAlarm(6, 30, "uthna"),
                AssistantAction.Flashlight(false),
                AssistantAction.Volume(60),
                AssistantAction.Media(MediaCommand.NEXT),
            ),
            parseReply(raw).actions,
        )
    }

    @Test
    fun rejectsOutOfRangeAndIncompleteActions() {
        val raw = listOf(
            """{"type":"set_timer","seconds":0}""",
            """{"type":"set_alarm","hour":25}""",
            """{"type":"volume","percent":150}""",
            """{"type":"sms","text":"hi"}""",
            """{"type":"media","command":"explode"}""",
        ).joinToString("") { "<action>$it</action>" }
        assertTrue(parseReply(raw).actions.isEmpty())
        assertEquals(
            AssistantAction.Sms("Mom", null, "Late aaunga"),
            parseReply("""<action>{"type":"sms","contact":"Mom","text":"Late aaunga"}</action>""").actions.single(),
        )
    }

    @Test
    fun acceptsNumbersInAnyJsonShape() {
        val raw = listOf(
            """{"type":"set_timer","minutes":1.5}""",
            """{"type":"set_alarm","hour":"7","minute":5.0}""",
            """{"type":"volume","percent":"40"}""",
            """{"type":"torch","state":"false"}""",
        ).joinToString("") { "<action>$it</action>" }
        assertEquals(
            listOf(AssistantAction.SetTimer(90, ""), AssistantAction.SetAlarm(7, 5, ""), AssistantAction.Volume(40), AssistantAction.Flashlight(false)),
            parseReply(raw).actions,
        )
    }

    @Test
    fun parsesCalendarNavigationAndCopy() {
        val raw = listOf(
            """{"type":"add_event","title":"Meeting","start":"2026-10-30 17:00","location":"Office"}""",
            """{"type":"navigate","destination":"India Gate","mode":"walk"}""",
            """{"type":"copy","text":"OK"}""",
            """{"type":"add_event","title":"Bad","start":"kal"}""",
        ).joinToString("") { "<action>$it</action>" }
        assertEquals(
            listOf(
                AssistantAction.AddEvent("Meeting", LocalDateTime.of(2026, 10, 30, 17, 0), 30, "Office"),
                AssistantAction.Navigate("India Gate", TravelMode.WALK),
                AssistantAction.CopyText("OK"),
            ),
            parseReply(raw).actions,
        )
    }

    @Test
    fun sharedTextIsWrappedAndTrimmed() {
        val request = buildSharedTextRequest("x".repeat(MAX_SHARED_CHARS + 50), "News")
        assertTrue(request.contains("Subject: News"))
        assertTrue(request.length < MAX_SHARED_CHARS + 400)
    }

    @Test
    fun parsesRepeatingEvents() {
        val a = parseReply("""<action>{"type":"add_event","title":"Class","start":"2026-10-30T08:00","repeat":"weekdays"}</action>""").actions.single() as AssistantAction.AddEvent
        assertEquals(EventRepeat.WEEKDAYS, a.repeat)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", a.repeat?.rrule)
        val once = parseReply("""<action>{"type":"add_event","title":"Doctor","start":"2026-10-30T08:00","repeat":"sometimes"}</action>""").actions.single() as AssistantAction.AddEvent
        assertEquals(null, once.repeat)
    }
}
