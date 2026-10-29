package com.raju.aiassistant.data.repository

import com.raju.aiassistant.data.db.BusinessDao
import com.raju.aiassistant.data.db.ExpenseEntity
import com.raju.aiassistant.data.db.JourneyEntity
import com.raju.aiassistant.domain.business.BusinessStore
import com.raju.aiassistant.domain.business.Expense
import com.raju.aiassistant.domain.business.Journey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class BusinessRepository(private val dao: BusinessDao, private val clock: () -> Long = System::currentTimeMillis) : BusinessStore {
    val recentExpenses: Flow<List<Expense>> = dao.observeRecentExpenses(50).map { rows -> rows.map { it.toDomain() } }

    fun upcomingJourneys(today: LocalDate): Flow<List<Journey>> =
        dao.observeJourneysFrom(today.toString()).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun addExpense(amountPaise: Long, note: String, category: String): Long =
        dao.insertExpense(ExpenseEntity(amountPaise = amountPaise, note = note.trim().take(80), category = category, createdAt = clock()))

    override suspend fun deleteLastExpense(): Expense? {
        val last = dao.lastExpense() ?: return null
        return if (dao.deleteExpense(last.id) > 0) last.toDomain() else null
    }

    suspend fun deleteExpense(id: Long): Boolean = dao.deleteExpense(id) > 0

    override suspend fun expensesBetween(fromMillis: Long, toMillis: Long): List<Expense> =
        dao.expensesBetween(fromMillis, toMillis).map { it.toDomain() }

    override suspend fun saveJourney(journey: Journey): Long {
        val date = journey.date.toString()
        val existing = dao.journeyByPnr(journey.pnr)
        if (existing != null) {
            // Keep details the user gave before when this update doesn't repeat them.
            dao.updateJourney(
                journey.pnr, journey.train ?: existing.train, date, journey.route ?: existing.route,
                journey.passenger ?: existing.passenger, journey.phone ?: existing.phone, journey.note ?: existing.note,
            )
            return existing.id
        }
        return dao.insertJourney(
            JourneyEntity(
                pnr = journey.pnr, train = journey.train, journeyDate = date, route = journey.route,
                passenger = journey.passenger, phone = journey.phone, note = journey.note, createdAt = clock(),
            ),
        )
    }

    override suspend fun journeysFrom(date: LocalDate): List<Journey> = dao.journeysFrom(date.toString()).mapNotNull { it.toDomain() }

    override suspend fun removeJourney(pnr: String): Boolean = dao.deleteJourney(pnr) > 0

    /** Journeys more than a week in the past are no longer useful. */
    suspend fun pruneOldJourneys(today: LocalDate) = dao.deleteJourneysBefore(today.minusDays(7).toString())
}

private fun ExpenseEntity.toDomain() = Expense(id, amountPaise, note, category, createdAt)

private fun JourneyEntity.toDomain(): Journey? = runCatching {
    Journey(id, pnr, train, LocalDate.parse(journeyDate), route, passenger, phone, note)
}.getOrNull()
