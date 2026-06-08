package com.iips.launcher.deployment

import android.util.Log
import com.iips.launcher.core.StructuredLogger
import com.iips.launcher.deployment.health.DeploymentRiskAnalyzer
import com.iips.launcher.deployment.rollback.DeploymentRecoveryService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the rollback of a failed or unhealthy enterprise rollout.
 */
@Singleton
class RollbackCoordinator @Inject constructor(
    private val riskAnalyzer: DeploymentRiskAnalyzer,
    private val checkpointManager: DeploymentCheckpointManager,
    private val convergenceTracker: DeploymentConvergenceTracker,
    private val recoveryService: DeploymentRecoveryService,
    private val structuredLogger: StructuredLogger
) {
    companion object {
        private const val TAG = "RollbackCoordinator"
    }

    /**
     * Checks if a rollback is required and executes it if necessary.
     */
    suspend fun checkAndTriggerRollback(deploymentId: String) {
        if (riskAnalyzer.shouldTriggerRollback()) {
            Log.e(TAG, "Critical health failure detected for \$deploymentId. Triggering rollback.")
            executeRollback(deploymentId, "Health score exceeded threshold")
        }
    }

    /**
     * Executes the rollback process.
     */
    suspend fun executeRollback(deploymentId: String, reason: String) {
        Log.w(TAG, "Executing rollback for \$deploymentId: \$reason")

        val checkpoint = checkpointManager.getCheckpoint(deploymentId)
        if (checkpoint == null) {
            Log.e(TAG, "Rollback failed: No checkpoint found for \$deploymentId")
            convergenceTracker.updateState(deploymentId, DeploymentConvergenceTracker.DeploymentState.FAILED, "Rollback failed: No checkpoint")
            return
        }

        // 1. Report ROLLED_BACK state
        convergenceTracker.updateState(deploymentId, DeploymentConvergenceTracker.DeploymentState.ROLLED_BACK, reason)

        // 2. Local State Reversion
        recoveryService.revertSystemState(
            checkpoint.activePoliciesHash,
            checkpoint.kioskEnabled
        )
        
        // 3. Telemetry Log
        structuredLogger.logIncident(
            TAG,
            "DEPLOYMENT_ROLLBACK",
            "Automatic rollback triggered: \$reason (Prev: \${checkpoint.previousVersionName})",
            fatal = true
        )

        // 4. Cleanup
        checkpointManager.clearCheckpoint(deploymentId)
        
        Log.i(TAG, "Rollback sequence completed for \$deploymentId")
    }
}
