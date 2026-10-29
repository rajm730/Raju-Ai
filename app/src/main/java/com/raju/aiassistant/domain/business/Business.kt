package com.raju.aiassistant.domain.business

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** One expense in the user's khata. Amounts are kept in paise so totals never round wrongly. */
data class Expense(val id: Long, val amountPaise: Long, val note: String, val category: String, val createdAt: Long)

/**
 * A train journey the user wants to keep track of (their own PNR diary). The live status is never
 * guessed: it is checked on the official Indian Railways page.
 */
data class Journey(
    val id: Long = 0,
    val pnr: String,
    val train: String? = null,
    val date: LocalDate,
    val route: String? = null,
    val passenger: String? = null,
    val phone: String? = null,
    val note: String? = null,
)

/** The user's business records, stored only on the phone. */
interface BusinessStore {
    suspend fun addExpense(amountPaise: Long, note: String, category: String): Long
    /** Removes the newest expense (an "undo"), or returns null if there is none. */
    suspend fun deleteLastExpense(): Expense?
    suspend fun expensesBetween(fromMillis: Long, toMillis: Long): List<Expense>
    /** Adds the journey, or updates it when the PNR is already saved. */
    suspend fun saveJourney(journey: Journey): Long
    suspend fun journeysFrom(date: LocalDate): List<Journey>
    suspend fun removeJourney(pnr: String): Boolean
}

object NoBusinessStore : BusinessStore {
    override suspend fun addExpense(amountPaise: Long, note: String, category: String) = 0L
    override suspend fun deleteLastExpense(): Expense? = null
    override suspend fun expensesBetween(fromMillis: Long, toMillis: Long) = emptyList<Expense>()
    override suspend fun saveJourney(journey: Journey) = 0L
    override suspend fun journeysFrom(date: LocalDate) = emptyList<Journey>()
    override suspend fun removeJourney(pnr: String) = false
}

enum class Period(val english: String, val hindi: String) {
    TODAY("today", "आज"),
    YESTERDAY("yesterday", "कल"),
    WEEK("this week", "इस हफ़्ते"),
    MONTH("this month", "इस महीने"),
}

/** [start, end) of a period in epoch millis, in the phone's time zone. Weeks start on Monday. */
fun periodRange(period: Period, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val (from, to) = when (period) {
        Period.TODAY -> today to today.plusDays(1)
        Period.YESTERDAY -> today.minusDays(1) to today
        Period.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to today.plusDays(1)
        Period.MONTH -> today.withDayOfMonth(1) to today.plusDays(1)
    }
    return from.atStartOfDay(zone).toInstant().toEpochMilli() to to.atStartOfDay(zone).toInstant().toEpochMilli()
}

/** "₹1,250" or "₹99.50" — Indian digit grouping (1,00,000). */
fun formatRupees(paise: Long): String {
    val rupees = paise / 100
    val rest = paise % 100
    val digits = rupees.toString()
    val grouped = if (digits.length <= 3) digits else {
        val head = digits.dropLast(3)
        head.reversed().chunked(2).joinToString(",").reversed() + "," + digits.takeLast(3)
    }
    return "₹" + grouped + if (rest != 0L) ".%02d".format(rest) else ""
}

/** Rupees as the model sends them (200, 99.5, "1,200") → paise; null if not a sensible amount. */
fun rupeesToPaise(value: String): Long? {
    val amount = value.replace(",", "").replace("₹", "").trim().toDoubleOrNull() ?: return null
    if (!amount.isFinite() || amount <= 0 || amount > 1_00_00_000) return null // up to ₹1 crore per entry
    return Math.round(amount * 100)
}

/** A PNR is exactly 10 digits (spaces and dashes allowed when spoken or typed). */
fun normalizePnr(value: String): String? = value.filter { it.isDigit() }.takeIf { it.length == 10 }

/** Short category names the assistant uses; anything else is kept as the user said it. */
fun normalizeCategory(value: String?): String {
    val v = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
    return when {
        v.isEmpty() -> "other"
        v in setOf("food", "khana", "chai", "tea", "snacks", "restaurant", "nashta") -> "food"
        v in setOf("fuel", "petrol", "diesel", "cng") -> "fuel"
        v in setOf("travel", "taxi", "auto", "bus", "train", "ticket", "cab", "uber", "ola") -> "travel"
        v in setOf("bill", "bills", "electricity", "bijli", "recharge", "mobile", "internet", "rent") -> "bills"
        v in setOf("shopping", "kapde", "clothes", "grocery", "kirana", "sabzi", "ration") -> "shopping"
        v in setOf("business", "stock", "maal", "purchase", "supplier") -> "business"
        v in setOf("health", "medicine", "dawai", "doctor") -> "health"
        else -> v.take(20)
    }
}

/** Spoken/written summary of expenses: total, count and the biggest categories. */
fun summarizeExpenses(expenses: List<Expense>, period: Period, hindi: Boolean): String {
    val label = if (hindi) period.hindi else period.english
    if (expenses.isEmpty()) return if (hindi) "$label का कोई खर्चा दर्ज नहीं है।" else "No expenses recorded $label."
    val total = expenses.sumOf { it.amountPaise }
    val byCategory = expenses.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amountPaise } }
        .entries.sortedByDescending { it.value }.take(3)
        .joinToString(", ") { "${it.key} ${formatRupees(it.value)}" }
    return if (hindi) "$label कुल खर्चा ${formatRupees(total)} (${expenses.size} entries) — सबसे ज़्यादा: $byCategory।"
    else "Total spent $label: ${formatRupees(total)} in ${expenses.size} entries — biggest: $byCategory."
}

private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

fun describeJourney(j: Journey): String = buildString {
    append(j.date.format(DAY)).append(" — PNR ").append(j.pnr)
    j.train?.let { append(", train ").append(it) }
    j.route?.let { append(", ").append(it) }
    j.passenger?.let { append(", ").append(it) }
    j.note?.let { append(" (").append(it).append(')') }
}

fun summarizeJourneys(journeys: List<Journey>, hindi: Boolean): String = when {
    journeys.isEmpty() -> if (hindi) "कोई आने वाली यात्रा सेव नहीं है।" else "No upcoming journeys saved."
    else -> (if (hindi) "आने वाली यात्राएँ:\n" else "Upcoming journeys:\n") + journeys.joinToString("\n") { "• " + describeJourney(it) }
}

/**
 * The user's live records for the system prompt, so Seema can answer "aaj kitna kharcha hua?" or
 * "kal kis ki train hai?" directly. Phone numbers are left out; null when there is nothing.
 */
fun businessContext(todayExpenses: List<Expense>, upcoming: List<Journey>): String? {
    if (todayExpenses.isEmpty() && upcoming.isEmpty()) return null
    return buildString {
        append("USER'S KHATA (their own records on this phone; use when relevant, never invent entries or PNR status):")
        if (todayExpenses.isNotEmpty()) {
            append("\n- Today's expenses: ").append(formatRupees(todayExpenses.sumOf { it.amountPaise }))
            append(" — ").append(todayExpenses.takeLast(6).joinToString(", ") { "${it.note} ${formatRupees(it.amountPaise)}" })
        }
        upcoming.take(5).forEach { append("\n- Journey: ").append(describeJourney(it.copy(phone = null))) }
    }
}

/** Morning notification text from local records only; null when there is nothing worth telling. */
fun morningBriefing(today: LocalDate, journeys: List<Journey>, yesterdayExpenses: List<Expense>): String? {
    val soon = journeys.filter { !it.date.isBefore(today) && !it.date.isAfter(today.plusDays(1)) }
    val parts = mutableListOf<String>()
    soon.forEach { parts += (if (it.date == today) "Aaj" else "Kal") + " yatra: " + describeJourney(it) }
    if (yesterdayExpenses.isNotEmpty()) parts += "Kal ka kharcha: " + formatRupees(yesterdayExpenses.sumOf { it.amountPaise })
    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
}
