package com.iips.launcher.selfheal

import android.content.Context
import android.util.Log
import com.iips.launcher.core.StructuredLogger
import com.iips.launcher.convergence.PolicyDriftResolver
import com.iips.launcher.reconciliation.DriftEvent
import com.iips.launcher.reconciliation.PolicyReconciliationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages autonomous self-healing actions based on detected drift.
 */
@Singleton
class SelfHealingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reconciliationManager: PolicyReconciliationManager,
    private val recoveryEngine: RuntimeRecoveryEngine,
    private val driftResolver: PolicyDriftResolver,
    private val structuredLogger: StructuredLogger
) {
    companion object {
        private const val TAG = "SelfHealing"
        private const val MIN_HEALING_INTERVAL_MS = 60 * 1000L // 1 minute
    }

    private var lastHealingTime = 0L

    /**
     * Triggers a self-healing cycle.
     */
    fun performHealing() {
        val now = System.currentTimeMillis()
        if (now - lastHealingTime < MIN_HEALING_INTERVAL_MS) {
            Log.d(TAG, "Skipping healing cycle: Rate-limited")
            return
        }
        lastHealingTime = now
        
        Log.i(TAG, "Starting self-healing cycle")
        
        val driftEvents = reconciliationManager.reconcile()
        if (driftEvents.isEmpty()) {
            Log.i(TAG, "No healing required: Device is healthy")
            return
        }

        structuredLogger.logEvent(
            TAG,
            "HEALING_START",
            "Starting remediation for \${driftEvents.size} drift events"
        )

        for (event in driftEvents) {
            try {
                recoveryEngine.remediate(event)
                driftResolver.resolve(event)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remediate event: \$event", e)
                structuredLogger.logIncident(TAG, "HEALING_FAILURE", "Remediation failed for \${event.javaClass.simpleName}")
            }
        }
    }
}
