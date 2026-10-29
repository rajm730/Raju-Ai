package com.raju.aiassistant.domain.assistant

import com.raju.aiassistant.domain.business.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BusinessAndToolsTest {
    private fun one(json: String) = parseReply("ok <action>$json</action>").actions.single()

    @Test
    fun parsesBusinessActions() {
        val e = one("""{"type":"expense_add","amount":"250.5","note":"petrol","category":"Petrol"}""") as AssistantAction.ExpenseAdd
        assertEquals(25_050L, e.amountPaise)
        assertEquals("fuel", e.category)
        assertEquals(Period.WEEK, (one("""{"type":"expense_summary","period":"week"}""") as AssistantAction.ExpenseSummary).period)
        val j = one("""{"type":"pnr_add","pnr":"123 456 7890","date":"02-11-2026","train":"12951"}""") as AssistantAction.JourneySave
        assertEquals("1234567890", j.journey.pnr)
        assertEquals(LocalDate.of(2026, 11, 2), j.journey.date)
        assertTrue(parseReply("""<action>{"type":"pnr_add","pnr":"123","date":"2026-11-02"}</action>""").actions.isEmpty())
        assertEquals("1234567890", (one("""{"type":"pnr_status","pnr":"1234567890"}""") as AssistantAction.PnrStatus).pnr)
        assertEquals("Onion", (one("""{"type":"mandi_price","commodity":"Onion","place":"Nashik"}""") as AssistantAction.MandiPrice).commodity)
    }

    @Test
    fun saveFileKeepsCodeIntactAndNamesSafe() {
        val f = one("""{"type":"save_file","name":"../my script.py","content":"def f():\n    return 1\n"}""") as AssistantAction.SaveFile
        assertEquals("my_script.py", f.name)
        assertEquals("def f():\n    return 1\n", f.content)
        assertEquals("notes.txt", safeFileName("notes"))
        assertNull(safeFileName("///"))
    }

    @Test
    fun researchTakesUpToThreeQueries() {
        val r = one("""{"type":"research","query":"best EV","queries":["a","b","c","d"]}""") as AssistantAction.Research
        assertEquals(listOf("a", "b", "c"), r.queries)
        assertEquals(listOf("solo"), (one("""{"type":"research","query":"solo"}""") as AssistantAction.Research).queries)
    }

    @Test
    fun smartMemoryPrefersRelatedFacts() {
        val memories = (1..20).map { "Fact number $it about nothing" } + "Mummy ka birthday 5 May ko hai"
        val picked = relevantMemories(memories, "mummy ke birthday par kya gift doon?", limit = 5)
        assertEquals("Mummy ka birthday 5 May ko hai", picked.first())
        assertEquals(5, picked.size)
        assertEquals(3, relevantMemories(listOf("a", "b", "c"), "x").size) // few memories: all of them
    }

    @Test
    fun promptListsBusinessToolsAndContext() {
        val p = buildSystemPrompt(emptyList(), "now", context = PromptContext(business = "USER'S KHATA: test"))
        assertTrue(p.contains("expense_add") && p.contains("pnr_status") && p.contains("save_file") && p.contains("research"))
        assertTrue(p.indexOf("USER'S KHATA") > p.indexOf("PHONE TOOLS"))
    }
}
