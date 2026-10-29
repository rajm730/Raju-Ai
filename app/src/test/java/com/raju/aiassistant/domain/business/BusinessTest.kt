package com.raju.aiassistant.domain.business

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class BusinessTest {
    private val utc = ZoneOffset.UTC

    @Test
    fun rupeesUseIndianGroupingAndPaise() {
        assertEquals("₹1,25,000", formatRupees(1_25_000_00))
        assertEquals("₹999", formatRupees(999_00))
        assertEquals("₹99.50", formatRupees(9_950))
        assertEquals(9_950L, rupeesToPaise("99.5"))
        assertEquals(1_20_000L, rupeesToPaise("1,200"))
        assertNull(rupeesToPaise("-5"))
        assertNull(rupeesToPaise("abc"))
    }

    @Test
    fun pnrMustBeTenDigits() {
        assertEquals("1234567890", normalizePnr("123-456 7890"))
        assertNull(normalizePnr("12345"))
    }

    @Test
    fun weekStartsOnMonday() {
        // Wednesday 29 Oct 2026, 15:00 UTC
        val now = LocalDateTime.of(2026, 10, 29, 15, 0).toInstant(utc).toEpochMilli()
        val (from, to) = periodRange(Period.WEEK, now, utc)
        assertEquals(LocalDate.of(2026, 10, 26).atStartOfDay(utc).toInstant().toEpochMilli(), from)
        assertEquals(LocalDate.of(2026, 10, 30).atStartOfDay(utc).toInstant().toEpochMilli(), to)
        val (y0, y1) = periodRange(Period.YESTERDAY, now, utc)
        assertEquals(24 * 3600_000L, y1 - y0)
    }

    @Test
    fun summaryNamesTotalAndBiggestCategories() {
        val list = listOf(Expense(1, 50_00, "chai", "food", 0), Expense(2, 400_00, "petrol", "fuel", 0), Expense(3, 30_00, "samosa", "food", 0))
        val s = summarizeExpenses(list, Period.TODAY, hindi = false)
        assertTrue(s, s.contains("₹480") && s.indexOf("fuel") < s.indexOf("food"))
        assertTrue(summarizeExpenses(emptyList(), Period.WEEK, hindi = true).contains("कोई खर्चा"))
    }

    @Test
    fun promptContextLeavesOutPhoneNumbers() {
        val j = Journey(pnr = "1234567890", train = "12951", date = LocalDate.of(2026, 11, 2), route = "Mumbai - Delhi", passenger = "Ramesh", phone = "9876543210")
        val ctx = businessContext(emptyList(), listOf(j))!!
        assertTrue(ctx.contains("1234567890") && ctx.contains("Ramesh") && !ctx.contains("9876543210"))
        assertNull(businessContext(emptyList(), emptyList()))
    }

    @Test
    fun morningBriefingOnlyWhenThereIsNews() {
        val today = LocalDate.of(2026, 11, 1)
        assertNull(morningBriefing(today, emptyList(), emptyList()))
        val text = morningBriefing(today, listOf(Journey(pnr = "1234567890", date = today.plusDays(1)), Journey(pnr = "1111111111", date = today.plusDays(5))), listOf(Expense(1, 120_00, "auto", "travel", 0)))!!
        assertTrue(text.contains("Kal yatra") && !text.contains("1111111111") && text.contains("₹120"))
    }

    @Test
    fun categoriesAreNormalised() {
        assertEquals("fuel", normalizeCategory("Petrol"))
        assertEquals("food", normalizeCategory("chai"))
        assertEquals("other", normalizeCategory(null))
    }
}
