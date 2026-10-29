package com.raju.aiassistant.domain.assistant

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
}
