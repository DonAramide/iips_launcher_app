package com.iips.launcher.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

import com.iips.launcher.telemetry.data.TelemetryEntity
import com.iips.launcher.apps.inventory.data.AppInventoryEntity
import com.iips.launcher.apps.inventory.data.AppEventEntity
import com.iips.launcher.data.entities.*
import com.iips.launcher.deployment.data.*
import com.iips.launcher.pocket.data.AppPocketEntity
import com.iips.launcher.pocket.data.AppPocketAuditEntity
import com.iips.launcher.pocket.data.AppPocketDao

@Database(
    entities = [
        AllowedApp::class, 
        AppUsageLog::class, 
        AppPolicy::class, 
        TelemetryEntity::class,
        AppInventoryEntity::class,
        AppEventEntity::class,
        RuntimeStateSnapshot::class,
        PolicyDriftEvent::class,
        RuntimeRecoveryEvent::class,
        DeploymentRecord::class,
        DeploymentCheckpoint::class,
        com.iips.launcher.guard.data.SecurityEventEntity::class,
        com.iips.launcher.guard.data.LocationReportEntity::class,
        com.iips.launcher.guard.data.GeofenceRuleEntity::class,
        com.iips.launcher.guard.data.TrackingSessionEntity::class,
        AppPocketEntity::class,
        AppPocketAuditEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun allowedAppDao(): AllowedAppDao
    abstract fun appUsageLogDao(): AppUsageLogDao
    abstract fun appPolicyDao(): AppPolicyDao
    abstract fun telemetryDao(): com.iips.launcher.telemetry.data.TelemetryDao
    abstract fun appInventoryDao(): com.iips.launcher.apps.inventory.data.AppInventoryDao
    abstract fun runtimeDao(): com.iips.launcher.data.entities.RuntimeDao
    abstract fun deploymentDao(): com.iips.launcher.deployment.data.DeploymentDao
    abstract fun guardEventDao(): com.iips.launcher.guard.data.GuardEventDao
    abstract fun locationReportDao(): com.iips.launcher.guard.data.LocationReportDao
    abstract fun geofenceRuleDao(): com.iips.launcher.guard.data.GeofenceRuleDao
    abstract fun trackingSessionDao(): com.iips.launcher.guard.data.TrackingSessionDao
    abstract fun appPocketDao(): AppPocketDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `app_pocket` (
                        `packageName` TEXT NOT NULL, 
                        `appName` TEXT NOT NULL, 
                        `versionName` TEXT NOT NULL, 
                        `versionCode` INTEGER NOT NULL, 
                        `apkPath` TEXT, 
                        `iconCachePath` TEXT, 
                        `installDate` INTEGER, 
                        `uninstallDate` INTEGER, 
                        `source` TEXT NOT NULL, 
                        `status` TEXT NOT NULL, 
                        `appType` TEXT NOT NULL, 
                        `isRequired` INTEGER NOT NULL, 
                        `isMissing` INTEGER NOT NULL, 
                        `lastUsedTimestamp` INTEGER, 
                        `storageUsageBytes` INTEGER NOT NULL, 
                        `crashCount` INTEGER NOT NULL, 
                        `healthStatus` TEXT NOT NULL, 
                        `updateAvailableVersion` TEXT, 
                        `addedAt` INTEGER NOT NULL, 
                        PRIMARY KEY(`packageName`)
                    )
                """.trimIndent())
                
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `app_pocket_audits` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `packageName` TEXT NOT NULL, 
                        `appName` TEXT NOT NULL, 
                        `versionName` TEXT NOT NULL, 
                        `versionCode` INTEGER NOT NULL, 
                        `actionType` TEXT NOT NULL, 
                        `userId` TEXT, 
                        `timestamp` INTEGER NOT NULL, 
                        `synced` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "launcher_database"
                )
                .addMigrations(MIGRATION_8_9)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}





