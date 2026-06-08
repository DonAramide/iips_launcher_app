package com.iips.launcher.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ManagerProfileEntity::class,
        PairedDeviceEntity::class,
        AlertFeedEntity::class,
        TrackedCoordinateEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class GuardMobileDatabase : RoomDatabase() {
    abstract fun managerProfileDao(): ManagerProfileDao
    abstract fun pairedDeviceDao(): PairedDeviceDao
    abstract fun alertFeedDao(): AlertFeedDao
    abstract fun trackedCoordinateDao(): TrackedCoordinateDao

    companion object {
        @Volatile
        private var INSTANCE: GuardMobileDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `paired_devices`")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `paired_devices` (
                        `deviceId` TEXT NOT NULL, 
                        `deviceName` TEXT NOT NULL, 
                        `status` TEXT NOT NULL, 
                        `lastSeen` INTEGER NOT NULL, 
                        `pairedAt` INTEGER NOT NULL, 
                        `branchId` TEXT NOT NULL, 
                        `branchName` TEXT NOT NULL, 
                        `merchantName` TEXT NOT NULL, 
                        `managerRole` TEXT, 
                        `lastSyncAt` INTEGER, 
                        PRIMARY KEY(`deviceId`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `paired_devices` RENAME TO `paired_devices_old`")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `paired_devices` (
                        `deviceId` TEXT NOT NULL, 
                        `deviceName` TEXT NOT NULL, 
                        `connectivityStatus` TEXT NOT NULL, 
                        `securityStatus` TEXT NOT NULL, 
                        `lastSeen` INTEGER NOT NULL, 
                        `pairedAt` INTEGER NOT NULL, 
                        `branchId` TEXT NOT NULL, 
                        `branchName` TEXT NOT NULL, 
                        `merchantName` TEXT NOT NULL, 
                        `managerRole` TEXT, 
                        `lastSyncAt` INTEGER, 
                        `batteryLevel` INTEGER, 
                        `networkStatus` TEXT, 
                        `guardStatus` TEXT, 
                        `deviceHealthStatus` TEXT NOT NULL, 
                        `unreadAlertCount` INTEGER NOT NULL, 
                        PRIMARY KEY(`deviceId`)
                    )
                """.trimIndent())
                
                db.execSQL("""
                    INSERT INTO `paired_devices` (
                        deviceId, deviceName, connectivityStatus, securityStatus, lastSeen, pairedAt, 
                        branchId, branchName, merchantName, managerRole, lastSyncAt, 
                        batteryLevel, networkStatus, guardStatus, deviceHealthStatus, unreadAlertCount
                    )
                    SELECT 
                        deviceId, deviceName, 
                        CASE WHEN status = 'OFFLINE' THEN 'OFFLINE' ELSE 'ONLINE' END, 
                        status, 
                        lastSeen, pairedAt, branchId, branchName, merchantName, managerRole, lastSyncAt,
                        NULL, NULL, NULL, 'HEALTHY', 0
                    FROM `paired_devices_old`
                """.trimIndent())
                
                db.execSQL("DROP TABLE IF EXISTS `paired_devices_old`")
            }
        }

        fun getDatabase(context: Context): GuardMobileDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GuardMobileDatabase::class.java,
                    "guard_mobile_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
