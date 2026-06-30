package com.iips.launcher.aai.store

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [AaiEventEntity::class], version = 1, exportSchema = false)
abstract class AaiDatabase : RoomDatabase() {
    abstract fun aaiEventDao(): AaiEventDao

    companion object {
        @Volatile
        private var INSTANCE: AaiDatabase? = null

        fun getDatabase(context: android.content.Context): AaiDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AaiDatabase::class.java,
                    "aai_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
