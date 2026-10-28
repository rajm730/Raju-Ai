package com.raju.aiassistant.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class RajuDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        fun create(context: Context): RajuDatabase =
            Room.databaseBuilder(context, RajuDatabase::class.java, "raju_ai.db")
                // Explicit migrations will be added with each schema change; schemas are exported
                // to app/schemas so migrations can be tested.
                .build()
    }
}
