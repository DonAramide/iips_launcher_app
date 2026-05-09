package com.iips.launcher.reconciliation

import android.util.Log
import com.iips.launcher.core.StructuredLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the policy reconciliation process.
 */
@Singleton
class PolicyReconciliationManager @Inject constructor(
    private val stateResolver: DeviceStateResolver,
    private val expectedStateCache: ExpectedStateCache,
    private val driftEngine: DriftDetectionEngine,
    private val structuredLogger: StructuredLogger
) {
    companion object {
        private const val TAG = "PolicyReconciliation"
    }

    private val _driftEvents = MutableStateFlow<List<DriftEvent>>(emptyList())
    val driftEvents: StateFlow<List<DriftEvent>> = _driftEvents

    /**
     * Reconciles the device state and updates drift events.
     */
    fun reconcile(): List<DriftEvent> {
        Log.i(TAG, "Starting reconciliation cycle")
        
        val actual = stateResolver.resolveActualState()
        val expected = expectedStateCache.getExpectedState()
        
        if (expected == null) {
            Log.w(TAG, "Reconciliation skipped: No expected state available")
            return emptyList()
        }

        val drift = driftEngine.detectDrift(expected, actual)
        _driftEvents.value = drift

        if (drift.isNotEmpty()) {
            reportDrift(drift)
        } else {
            Log.i(TAG, "Reconciliation successful: Device in convergence")
        }

        return drift
    }

    private fun reportDrift(events: List<DriftEvent>) {
        structuredLogger.logEvent(
            TAG,
            "POLICY_DRIFT",
            "Drift detected during reconciliation",
            mapOf("events" to events.map { it.javaClass.simpleName })
        )
    }
}
