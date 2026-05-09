package com.iips.launcher.deployment

import android.content.Context
import android.util.Log
import com.iips.launcher.deployment.data.DeploymentCheckpoint
import com.iips.launcher.deployment.data.DeploymentDao
import com.iips.launcher.kiosk.KioskManager
import com.iips.launcher.policy.PolicyRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages deployment checkpoints for safe rollback.
 */
@Singleton
class DeploymentCheckpointManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deploymentDao: DeploymentDao,
    private val policyRepository: PolicyRepository,
    private val kioskManager: KioskManager
) {
    companion object {
        private const val TAG = "CheckpointManager"
    }

    /**
     * Creates a checkpoint for the current system state.
     */
    suspend fun createCheckpoint(deploymentId: String) {
        Log.i(TAG, "Creating checkpoint for deployment: \$deploymentId")

        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        
        val checkpoint = DeploymentCheckpoint(
            deploymentId = deploymentId,
            timestamp = System.currentTimeMillis(),
            previousVersionName = packageInfo.versionName,
            previousVersionCode = packageInfo.versionCode,
            activePoliciesHash = policyRepository.currentPolicy.value.hashCode().toString(),
            kioskEnabled = kioskManager.isKioskEnabled()
        )

        deploymentDao.insertCheckpoint(checkpoint)
    }

    /**
     * Retrieves the checkpoint for a specific deployment.
     */
    suspend fun getCheckpoint(deploymentId: String): DeploymentCheckpoint? {
        return deploymentDao.getCheckpointForDeployment(deploymentId)
    }

    /**
     * Clears a checkpoint after successful convergence.
     */
    suspend fun clearCheckpoint(deploymentId: String) {
        deploymentDao.deleteCheckpoint(deploymentId)
    }
}
