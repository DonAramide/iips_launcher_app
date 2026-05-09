package com.iips.launcher.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import com.iips.launcher.telemetry.data.TelemetryEntity
import com.iips.launcher.apps.inventory.data.AppInventoryEntity
import com.iips.launcher.apps.inventory.data.AppEventEntity
import com.iips.launcher.data.entities.*
import com.iips.launcher.deployment.data.*

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
        DeploymentCheckpoint::class
    ],
    version = 6,
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

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "launcher_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}





