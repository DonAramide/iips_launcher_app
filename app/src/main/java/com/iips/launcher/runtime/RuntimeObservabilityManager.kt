package com.iips.launcher.runtime

import android.util.Log
import com.iips.launcher.core.StructuredLogger
import com.iips.launcher.data.entities.PolicyDriftEvent
import com.iips.launcher.data.entities.RuntimeDao
import com.iips.launcher.data.entities.RuntimeRecoveryEvent
import com.iips.launcher.data.entities.RuntimeStateSnapshot
import com.iips.launcher.health.HealthReport
import com.iips.launcher.presence.DevicePresenceManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the persistent observability of runtime events, drift, and recovery actions.
 */
@Singleton
class RuntimeObservabilityManager @Inject constructor(
    private val runtimeDao: RuntimeDao,
    private val structuredLogger: StructuredLogger
) {
    companion object {
        private const val TAG = "RuntimeObservability"
    }

    /**
     * Records a health snapshot both locally and to telemetry.
     */
    suspend fun recordSnapshot(state: DevicePresenceManager.PresenceState, report: HealthReport, driftCount: Int) {
        val snapshot = RuntimeStateSnapshot(
            timestamp = System.currentTimeMillis(),
            state = state.name,
            isWorkManagerHealthy = report.isWorkManagerHealthy,
            isDatabaseHealthy = report.isDatabaseHealthy,
            isTelemetryHealthy = report.isTelemetryHealthy,
            driftCount = driftCount
        )
        
        runtimeDao.insertSnapshot(snapshot)
        
        structuredLogger.logEvent(
            TAG,
            "RUNTIME_SNAPSHOT",
            "Periodic runtime health snapshot",
            mapOf(
                "state" to state.name,
                "drift_count" to driftCount,
                "report" to report
            )
        )
    }

    /**
     * Records a drift detection event.
     */
    suspend fun recordDrift(driftType: String, details: String) {
        runtimeDao.insertDriftEvent(PolicyDriftEvent(
            timestamp = System.currentTimeMillis(),
            driftType = driftType,
            details = details
        ))
    }

    /**
     * Records a recovery attempt.
     */
    suspend fun recordRecovery(type: String, action: String, success: Boolean, error: String? = null) {
        runtimeDao.insertRecoveryEvent(RuntimeRecoveryEvent(
            timestamp = System.currentTimeMillis(),
            eventType = type,
            actionTaken = action,
            success = success,
            errorMessage = error
        ))
        
        if (success) {
            Log.i(TAG, "Recovery successful: \$action")
        } else {
            Log.e(TAG, "Recovery failed: \$action - \$error")
        }
    }
}
