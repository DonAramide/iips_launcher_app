package com.iips.launcher.deployment.verification

import android.util.Log
import com.iips.launcher.deployment.DeploymentConvergenceTracker
import com.iips.launcher.reconciliation.PolicyReconciliationManager
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates the success of a deployment after installation.
 */
@Singleton
class DeploymentVerifier @Inject constructor(
    private val healthService: com.iips.launcher.deployment.health.RolloutHealthService,
    private val reconciliationManager: PolicyReconciliationManager,
    private val convergenceTracker: DeploymentConvergenceTracker
) {
    companion object {
        private const val TAG = "DeploymentVerifier"
        private const val VALIDATION_WINDOW_MS = 5 * 60 * 1000L // 5 minutes
    }

    /**
     * Runs the post-install validation sequence.
     */
    suspend fun verifyDeployment(deploymentId: String): Boolean {
        Log.i(TAG, "Starting post-install validation for \$deploymentId")
        convergenceTracker.updateState(deploymentId, DeploymentConvergenceTracker.DeploymentState.VALIDATING)

        // 1. Initial Stability Check
        if (healthService.getHealthReport().isUnhealthy()) {
            Log.e(TAG, "Deployment \$deploymentId failed initial stability check")
            return false
        }

        // 2. Policy Convergence Check
        val drift = reconciliationManager.reconcile()
        if (drift.isNotEmpty()) {
            Log.w(TAG, "Drift detected after update. Waiting for convergence...")
            // We allow some time for convergence
            delay(30000)
            if (reconciliationManager.reconcile().isNotEmpty()) {
                Log.e(TAG, "Deployment \$deploymentId failed policy convergence")
            }
        }

        // 3. Stability Monitoring Window
        Log.i(TAG, "Entering stability monitoring window (\${VALIDATION_WINDOW_MS / 60000}m)")
        delay(VALIDATION_WINDOW_MS)

        val report = healthService.getHealthReport()
        return if (!report.isUnhealthy()) {
            Log.i(TAG, "Deployment \$deploymentId successfully validated")
            convergenceTracker.updateState(deploymentId, DeploymentConvergenceTracker.DeploymentState.CONVERGED)
            true
        } else {
            Log.e(TAG, "Deployment \$deploymentId failed stability window")
            false
        }
    }
}
