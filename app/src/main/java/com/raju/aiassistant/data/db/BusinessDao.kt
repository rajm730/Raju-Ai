package com.raju.aiassistant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BusinessDao {
    @Insert
    suspend fun insertExpense(expense: ExpenseEntity): Long

    @Query("SELECT * FROM expenses ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun lastExpense(): ExpenseEntity?

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun deleteExpense(id: Long): Int

    @Query("SELECT * FROM expenses WHERE createdAt >= :from AND createdAt < :to ORDER BY createdAt ASC, id ASC")
    suspend fun expensesBetween(from: Long, to: Long): List<ExpenseEntity>

    @Query("SELECT * FROM expenses ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecentExpenses(limit: Int): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM journeys WHERE pnr = :pnr LIMIT 1")
    suspend fun journeyByPnr(pnr: String): JourneyEntity?

    @Insert
    suspend fun insertJourney(journey: JourneyEntity): Long

    @Query("UPDATE journeys SET train = :train, journeyDate = :date, route = :route, passenger = :passenger, phone = :phone, note = :note WHERE pnr = :pnr")
    suspend fun updateJourney(pnr: String, train: String?, date: String, route: String?, passenger: String?, phone: String?, note: String?): Int

    /** ISO dates sort correctly as text. */
    @Query("SELECT * FROM journeys WHERE journeyDate >= :fromDate ORDER BY journeyDate ASC, id ASC")
    suspend fun journeysFrom(fromDate: String): List<JourneyEntity>

    @Query("SELECT * FROM journeys WHERE journeyDate >= :fromDate ORDER BY journeyDate ASC, id ASC")
    fun observeJourneysFrom(fromDate: String): Flow<List<JourneyEntity>>

    @Query("DELETE FROM journeys WHERE pnr = :pnr")
    suspend fun deleteJourney(pnr: String): Int

    @Query("DELETE FROM journeys WHERE journeyDate < :beforeDate")
    suspend fun deleteJourneysBefore(beforeDate: String): Int
}
