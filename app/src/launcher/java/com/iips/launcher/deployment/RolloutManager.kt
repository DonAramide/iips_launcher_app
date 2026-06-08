package com.iips.launcher.deployment

import android.util.Log
import com.iips.launcher.deployment.data.DeploymentDao
import com.iips.launcher.deployment.data.DeploymentRecord
import com.iips.launcher.install.SilentInstallEngine
import com.iips.launcher.network.models.MdmCommand
import com.iips.launcher.deployment.verification.DeploymentVerifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context

/**
 * Orchestrates the execution of enterprise rollouts on the device.
 */
@Singleton
class RolloutManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deploymentDao: DeploymentDao,
    private val convergenceTracker: DeploymentConvergenceTracker,
    private val cohortService: com.iips.launcher.deployment.rollout.RolloutCohortService,
    private val segmentationEngine: com.iips.launcher.deployment.rollout.RolloutSegmentationEngine,
    private val stagedInstallEngine: StagedInstallEngine,
    private val deploymentVerifier: DeploymentVerifier,
    private val rollbackCoordinator: RollbackCoordinator
) {
    @Volatile
    private var isRolloutFrozen: Boolean = false

    fun setRolloutFrozen(frozen: Boolean) {
        this.isRolloutFrozen = frozen
        Log.w(TAG, "Rollout execution state changed: Frozen=\$frozen")
    }
    companion object {
        private const val TAG = "RolloutManager"
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Initiates a rollout from an MDM command.
     */
    fun handleRolloutCommand(command: MdmCommand) {
        val deploymentId = command.id
        
        scope.launch {
            // 1. Safety Check: Is there already an active deployment?
            val existing = deploymentDao.getAllDeployments()
                .find { it.state != DeploymentConvergenceTracker.DeploymentState.CONVERGED.name && 
                         it.state != DeploymentConvergenceTracker.DeploymentState.FAILED.name &&
                         it.state != DeploymentConvergenceTracker.DeploymentState.ROLLED_BACK.name }
            
            if (existing != null) {
                Log.e(TAG, "Cannot start rollout \$deploymentId: Deployment \${existing.deploymentId} is already in progress (\${existing.state})")
                convergenceTracker.updateState(deploymentId, DeploymentConvergenceTracker.DeploymentState.FAILED, "Concurrent deployment conflict")
                return@launch
            }

            // 2. Blast Radius Containment: Check for recent rollbacks
            val recentRollbacks = deploymentDao.getAllDeployments()
                .filter { it.state == DeploymentConvergenceTracker.DeploymentState.ROLLED_BACK.name && 
                          System.currentTimeMillis() - it.startTime < 3600000 } // last hour
            
            if (recentRollbacks.size >= 2) {
                Log.e(TAG, "Rollout \$deploymentId blocked: Too many recent rollbacks detected")
                convergenceTracker.updateState(deploymentId, DeploymentConvergenceTracker.DeploymentState.FAILED, "Deployment safety threshold exceeded")
                return@launch
            }

            // 3. Safety Gate: Rollout Freeze
            if (isRolloutFrozen) {
                Log.w(TAG, "Deployment \$deploymentId blocked: Rollout system is FROZEN")
                convergenceTracker.updateState(deploymentId, DeploymentConvergenceTracker.DeploymentState.FAILED, "Rollout system frozen")
                return@launch
            }

            // For demonstration, we assume command payload has the details
            val apkUrl = "https://example.com/apk" 
            val version = "1.1.0"
            val expectedHash = "sha256..."
            val targetCohorts = listOf("COHORT_PRODUCTION", "TENANT_DEMO")
            val targetPercentage = 10 // 10% rollout

            // 4. Cohort & Segmentation Targeting
            if (!cohortService.matchesTarget(targetCohorts)) {
                Log.w(TAG, "Device not in target cohorts \$targetCohorts. Skipping.")
                convergenceTracker.updateState(deploymentId, DeploymentConvergenceTracker.DeploymentState.FAILED, "Cohort mismatch")
                return@launch
            }

            if (!segmentationEngine.isEligible(targetPercentage, deploymentId)) {
                Log.i(TAG, "Device not in target percentage bucket (\$targetPercentage%). Skipping.")
                convergenceTracker.updateState(deploymentId, DeploymentConvergenceTracker.DeploymentState.FAILED, "Percentage bucket exclusion")
                return@launch
            }

            // 5. Record the deployment
            val record = DeploymentRecord(
                deploymentId = deploymentId,
                version = version,
                apkUrl = apkUrl,
                cohortId = targetCohorts.joinToString(","),
                state = DeploymentConvergenceTracker.DeploymentState.PENDING.name,
                startTime = System.currentTimeMillis()
            )
            deploymentDao.insertDeployment(record)

            // 6. Start execution
            executeRollout(deploymentId, apkUrl, version, expectedHash)
        }
    }

    private suspend fun executeRollout(deploymentId: String, apkUrl: String, version: String, expectedHash: String) {
        convergenceTracker.updateState(deploymentId, DeploymentConvergenceTracker.DeploymentState.DOWNLOADING)
        
        // Simulating download to a temporary file
        val tempApk = java.io.File(context.cacheDir, "deployment_\$deploymentId.apk")
        
        Log.i(TAG, "Executing staged install for \$version")
        stagedInstallEngine.executeStagedInstall(deploymentId, tempApk, context.packageName, expectedHash)
        
        // The actual installation will cause a reboot/launcher restart.
        // The 'VALIDATING' and 'CONVERGED' states will be handled by a startup check.
    }

    /**
     * Resumes validation after a restart if a deployment was in progress.
     */
    fun resumeDeploymentValidation() {
        scope.launch {
            val activeDeployments = deploymentDao.getAllDeployments()
                .filter { it.state == DeploymentConvergenceTracker.DeploymentState.INSTALLING.name || 
                          it.state == DeploymentConvergenceTracker.DeploymentState.VALIDATING.name }
            
            for (deployment in activeDeployments) {
                val success = deploymentVerifier.verifyDeployment(deployment.deploymentId)
                if (!success) {
                    rollbackCoordinator.executeRollback(deployment.deploymentId, "Post-install validation failed")
                }
            }
        }
    }
}
