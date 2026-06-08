package com.iips.launcher.health

import android.util.Log
import com.iips.launcher.presence.DevicePresenceManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level coordinator that translates health reports into presence states.
 */
@Singleton
class HealthCheckCoordinator @Inject constructor(
    private val healthMonitor: RuntimeHealthMonitor,
    private val presenceManager: DevicePresenceManager
) {
    companion object {
        private const val TAG = "HealthCheck"
    }

    /**
     * Executes a coordinated health check.
     */
    fun checkHealth() {
        Log.i(TAG, "Starting coordinated health check")
        val report = healthMonitor.performHealthScan()
        
        if (!report.isOverallHealthy()) {
            Log.w(TAG, "Health check failed: \${report}")
            
            val failureReason = buildString {
                if (!report.isWorkManagerHealthy) append("WorkManager failure. ")
                if (!report.isDatabaseHealthy) append("Database failure. ")
                if (!report.isTelemetryHealthy) append("Telemetry failure. ")
            }
            
            presenceManager.updateState(DevicePresenceManager.PresenceState.DEGRADED, failureReason)
        } else {
            // Only move back to ONLINE if we were DEGRADED/STALE, not QUARANTINED
            if (presenceManager.currentState.value == DevicePresenceManager.PresenceState.DEGRADED) {
                presenceManager.updateState(DevicePresenceManager.PresenceState.ONLINE, "Subsystems recovered")
            }
        }
    }
}
