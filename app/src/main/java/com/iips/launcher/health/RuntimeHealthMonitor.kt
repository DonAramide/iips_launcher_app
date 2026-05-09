package com.iips.launcher.health

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors the granular health of internal launcher subsystems.
 */
@Singleton
class RuntimeHealthMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "RuntimeHealth"
    }

    /**
     * Verifies if WorkManager is functioning.
     */
    fun isWorkManagerHealthy(): Boolean {
        return try {
            WorkManager.getInstance(context).getWorkInfosByTag("dotoid_telemetry").get()
            true
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager health check failed", e)
            false
        }
    }

    /**
     * Verifies if the local database is operational.
     */
    fun isDatabaseHealthy(): Boolean {
        // In a real app, you'd perform a lightweight query
        return true 
    }

    /**
     * Checks if telemetry queue is backed up.
     */
    fun isTelemetryHealthy(): Boolean {
        // Placeholder for checking queue size or last success time
        return true
    }

    /**
     * Performs a full health scan.
     */
    fun performHealthScan(): HealthReport {
        return HealthReport(
            isWorkManagerHealthy = isWorkManagerHealthy(),
            isDatabaseHealthy = isDatabaseHealthy(),
            isTelemetryHealthy = isTelemetryHealthy(),
            timestamp = System.currentTimeMillis()
        )
    }
}

/**
 * Data class representing the health of the runtime.
 */
data class HealthReport(
    val isWorkManagerHealthy: Boolean,
    val isDatabaseHealthy: Boolean,
    val isTelemetryHealthy: Boolean,
    val timestamp: Long
) {
    fun isOverallHealthy(): Boolean = isWorkManagerHealthy && isDatabaseHealthy && isTelemetryHealthy
}
