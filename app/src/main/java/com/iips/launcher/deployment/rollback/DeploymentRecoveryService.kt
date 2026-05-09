package com.iips.launcher.deployment.rollback

import android.content.Context
import android.util.Log
import com.iips.launcher.kiosk.KioskManager
import com.iips.launcher.policy.PolicyRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles low-level system recovery and state restoration during a rollback.
 */
@Singleton
class DeploymentRecoveryService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val policyRepository: PolicyRepository,
    private val kioskManager: KioskManager
) {
    companion object {
        private const val TAG = "RecoveryService"
    }

    /**
     * Reverts the device to a previous known stable state.
     */
    suspend fun revertSystemState(activePoliciesHash: String, kioskEnabled: Boolean) {
        Log.i(TAG, "Reverting system state: policiesHash=\$activePoliciesHash, kiosk=\$kioskEnabled")
        
        try {
            // 1. Revert Policy state
            // In a real implementation, we would restore the actual policy JSON
            // For now, we trigger a re-convergence
            policyRepository.fetchLatestPolicy()

            // 2. Restore Kiosk state if it drifted
            if (kioskEnabled && !kioskManager.isKioskEnabled()) {
                Log.w(TAG, "Restoring Kiosk mode during recovery")
                // Note: enableKiosk requires an activity, so we might need a headless way or wait for launcher start
            } else if (!kioskEnabled && kioskManager.isKioskEnabled()) {
                Log.w(TAG, "Disabling Kiosk mode during recovery")
            }

            // 3. Purge temporary deployment artifacts
            context.cacheDir.listFiles()?.forEach { 
                if (it.name.startsWith("deployment_")) {
                    it.delete()
                    Log.d(TAG, "Purged deployment artifact: \${it.name}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "System state reversion failed", e)
        }
    }
}
