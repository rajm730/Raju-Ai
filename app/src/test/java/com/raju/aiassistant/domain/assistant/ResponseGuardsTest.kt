package com.raju.aiassistant.domain.assistant

import com.raju.aiassistant.domain.ai.AiModel
import com.raju.aiassistant.domain.ai.ModelAdvice
import com.raju.aiassistant.domain.ai.ProviderId
import com.raju.aiassistant.domain.ai.pickDefaultModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseGuardsTest {
    @Test
    fun stuckLoopIsCollapsedToOneCopy() {
        val loop = "Main aapki madad karungi. " + "aap kaise ho ".repeat(8) + "Dhanyavaad."
        val r = collapseRepetition(loop)
        assertTrue(r.degenerate)
        assertEquals("Main aapki madad karungi. aap kaise ho Dhanyavaad.", r.text)
    }

    @Test
    fun normalEmphasisIsKept() {
        listOf(
            "Bahut bahut dhanyavaad, aapka din shubh ho!",
            "Haan haan, main kar dungi. Koi baat nahi.",
            "Step 1: open. Step 2: open. Step 3: close the app now.",
        ).forEach { assertFalse(it, collapseRepetition(it).degenerate) }
    }

    @Test
    fun singleWordNeedsSixRepeats() {
        assertFalse(collapseRepetition("na na na na na theek hai yeh").degenerate)
        assertTrue(collapseRepetition("ha ha ha ha ha ha ha theek hai").degenerate)
    }

    @Test
    fun duplicateSubmitIsIgnoredOnlyWithinWindow() {
        val g = SubmitGuard(windowMs = 1_500)
        assertTrue(g.accept("Mausam kaisa hai?", 0))
        assertFalse(g.accept("mausam kaisa hai? ", 500)) // double tap
        assertTrue(g.accept("Mausam kaisa hai?", 5_000)) // asked again later: fine
        assertTrue(g.accept("Kuch aur", 5_100))
    }

    @Test
    fun memoryValidation() {
        assertEquals(MemoryCheck.Ok("Favourite colour is blue"), validateMemory("  Favourite   colour is blue ", emptyList()))
        assertTrue(validateMemory("", emptyList()) is MemoryCheck.Rejected)
        assertTrue(validateMemory("x".repeat(MAX_MEMORY_CHARS + 1), emptyList()) is MemoryCheck.Rejected)
        assertTrue(validateMemory("my OTP is 4455", emptyList()) is MemoryCheck.Rejected)
        assertTrue(validateMemory("favourite colour is BLUE", listOf("Favourite colour is blue")) is MemoryCheck.Rejected)
        assertTrue(validateMemory("new fact", List(MAX_MEMORIES) { "m$it" }) is MemoryCheck.Rejected)
    }

    @Test
    fun allamIsFlaggedAndNeverPickedByDefault() {
        val groq = listOf("allam-2-7b", "compound-beta", "llama-3.1-8b-instant", "llama-3.3-70b-versatile", "whisper-large-v3", "meta-llama/llama-guard-4-12b").map { AiModel(it, it) }
        assertNotNull(ModelAdvice.warning("allam-2-7b"))
        assertNull(ModelAdvice.warning("llama-3.3-70b-versatile"))
        assertEquals("llama-3.3-70b-versatile", pickDefaultModel(ProviderId.GROQ, groq)?.id)
        val ranked = ModelAdvice.rank(ProviderId.GROQ, groq).map { it.id }
        assertEquals("llama-3.3-70b-versatile", ranked.first())
        assertTrue(ranked.indexOf("allam-2-7b") > ranked.indexOf("llama-3.1-8b-instant"))
        assertEquals(6_000, ModelAdvice.historyCharBudget("allam-2-7b"))
    }

    @Test
    fun geminiPrefersFlashNotLite() {
        val gemini = listOf("gemini-2.5-flash-lite", "gemini-2.5-pro", "gemini-2.5-flash", "text-embedding-004").map { AiModel(it, it) }
        assertEquals("gemini-2.5-flash", pickDefaultModel(ProviderId.GEMINI, gemini)?.id)
    }
}
