package com.iips.launcher.deployment

import android.content.Context
import android.util.Log
import com.iips.launcher.install.SilentInstallEngine
import com.iips.launcher.install.InstallVerificationEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A transactional wrapper around the SilentInstallEngine for enterprise rollouts.
 */
@Singleton
class StagedInstallEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val silentInstallEngine: SilentInstallEngine,
    private val verificationEngine: InstallVerificationEngine,
    private val checkpointManager: DeploymentCheckpointManager,
    private val convergenceTracker: DeploymentConvergenceTracker
) {
    companion object {
        private const val TAG = "StagedInstall"
    }

    /**
     * Executes a staged installation with checkpoints and tracking.
     */
    suspend fun executeStagedInstall(deploymentId: String, apkFile: File, packageName: String, expectedHash: String) {
        Log.i(TAG, "Starting staged install for \$deploymentId")

        try {
            // 1. Verify APK
            convergenceTracker.updateState(deploymentId, DeploymentConvergenceTracker.DeploymentState.VERIFYING)
            val isValid = verificationEngine.validateApk(apkFile, packageName, expectedHash)
            if (!isValid) {
                convergenceTracker.recordFailure(deploymentId, "Verification failed: APK validation failed")
                return
            }

            // 2. Create Checkpoint
            checkpointManager.createCheckpoint(deploymentId)

            // 3. Trigger Install
            convergenceTracker.updateState(deploymentId, DeploymentConvergenceTracker.DeploymentState.INSTALLING)
            
            // Note: SilentInstallEngine handles the broadcast and completion.
            // We need to ensure that the completion is linked to our deploymentId.
            // For now, we trigger it and the system will move to VALIDATING on next boot or session completion.
            silentInstallEngine.installSilently(apkFile, packageName)
            
        } catch (e: Exception) {
            Log.e(TAG, "Staged install failed", e)
            convergenceTracker.recordFailure(deploymentId, e.message ?: "Unknown error")
        }
    }
}
