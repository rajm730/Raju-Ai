package com.raju.aiassistant.domain.assistant

import com.raju.aiassistant.domain.ai.Turn
import com.raju.aiassistant.domain.ai.TurnRole
import com.raju.aiassistant.domain.model.ChatMessage
import com.raju.aiassistant.domain.model.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAndTextTest {
    private fun msg(role: ChatRole, text: String) = ChatMessage(0, 0, role, text, 0)

    @Test
    fun turnsMergeSameRoleAndStartWithUser() {
        val turns = toTurns(
            listOf(msg(ChatRole.ASSISTANT, "old"), msg(ChatRole.USER, "a"), msg(ChatRole.SYSTEM, "done"), msg(ChatRole.ASSISTANT, "b")),
            limit = 10,
        )
        assertEquals(listOf(Turn(TurnRole.USER, "a\n\n[App note] done"), Turn(TurnRole.ASSISTANT, "b")), turns)
    }

    @Test
    fun turnsRespectLimitAndCharBudget() {
        val many = (1..50).map { msg(if (it % 2 == 1) ChatRole.USER else ChatRole.ASSISTANT, "x".repeat(100)) }
        assertTrue(toTurns(many, limit = 10).size <= 10)
        val budget = toTurns(many, limit = 50, maxChars = 350)
        assertTrue(budget.sumOf { it.text.length } <= 350)
        assertEquals(TurnRole.USER, budget.first().role)
    }

    @Test
    fun systemPromptIncludesToolsAndMemories() {
        val p = buildSystemPrompt(listOf("Mom's name is Sita"), "today")
        assertTrue(p.contains("\"type\":\"call\""))
        assertTrue(p.contains("Mom's name is Sita"))
    }

    @Test
    fun speakableTextRemovesMarkdownLinksAndCitations() {
        val s = speakableText("**Namaste!** Weather is 30° [1].\n- Source: https://example.com/x")
        assertEquals("Namaste! Weather is 30° .\nSource:", s)
    }

    @Test
    fun chunksStayUnderLimitAndKeepAllText() {
        val text = (1..40).joinToString(" ") { "Yeh vakya number $it hai।" }
        val chunks = chunkForSpeech(text, maxChars = 120)
        assertTrue(chunks.all { it.length <= 120 })
        assertEquals(text.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
    }

    @Test
    fun urlSafety() {
        assertTrue(isSafeWebUrl("https://news.example.com/a?b=1"))
        assertFalse(isSafeWebUrl("javascript:alert(1)"))
        assertFalse(isSafeWebUrl("https://user@evil.com"))
        assertFalse(isSafeWebUrl("file:///sdcard/x"))
    }

    @Test
    fun sensitiveDetection() {
        assertTrue(looksSensitive("my password is hunter2"))
        assertTrue(looksSensitive("card 4111 1111 1111 1111"))
        assertTrue(looksSensitive("key AIzaSyDabcdefghijklmnopqrstuv"))
        assertTrue(looksSensitive("मेरा पिन 1234"))
        assertFalse(looksSensitive("My favourite colour is blue"))
    }
}
