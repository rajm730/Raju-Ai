package com.raju.aiassistant.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, MemoryEntity::class, ActionLogEntity::class, ExpenseEntity::class, JourneyEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class RajuDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemoryDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun businessDao(): BusinessDao

    companion object {
        /** v1 → v2: adds memories and action history. Existing chats are kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `action_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `summary` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            }
        }

        /**
         * v2 → v3: adds the khata (expenses) and the PNR diary (journeys). Existing data is kept.
         * The SQL is exactly what Room generates for the entities (checked by RajuDatabaseSchemaTest).
         */
        val MIGRATION_2_3_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `expenses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `amountPaise` INTEGER NOT NULL, `note` TEXT NOT NULL, `category` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_expenses_createdAt` ON `expenses` (`createdAt`)",
            "CREATE TABLE IF NOT EXISTS `journeys` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `pnr` TEXT NOT NULL, `train` TEXT, `journeyDate` TEXT NOT NULL, `route` TEXT, `passenger` TEXT, `phone` TEXT, `note` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_journeys_pnr` ON `journeys` (`pnr`)",
        )

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3_SQL.forEach { db.execSQL(it) }
            }
        }

        fun create(context: Context): RajuDatabase =
            Room.databaseBuilder(context, RajuDatabase::class.java, "raju_ai.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
