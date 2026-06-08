package com.iips.launcher.deployment

import android.util.Log
import com.iips.launcher.core.StructuredLogger
import com.iips.launcher.deployment.data.DeploymentDao
import com.iips.launcher.deployment.data.DeploymentRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the real-time convergence state of a deployment.
 */
@Singleton
class DeploymentConvergenceTracker @Inject constructor(
    private val deploymentDao: DeploymentDao,
    private val structuredLogger: StructuredLogger
) {
    companion object {
        private const val TAG = "DeploymentConvergence"
    }

    enum class DeploymentState {
        PENDING,
        DOWNLOADING,
        VERIFYING,
        INSTALLING,
        VALIDATING,
        CONVERGED,
        FAILED,
        ROLLED_BACK
    }

    private val _currentState = MutableStateFlow<Pair<String, DeploymentState>?>(null) // deploymentId to state
    val currentState: StateFlow<Pair<String, DeploymentState>?> = _currentState

    /**
     * Updates the state of a specific deployment.
     */
    suspend fun updateState(deploymentId: String, newState: DeploymentState, error: String? = null) {
        Log.i(TAG, "Deployment \$deploymentId transition -> \$newState")
        _currentState.value = deploymentId to newState

        val record = deploymentDao.getDeploymentById(deploymentId)
        record?.let {
            val updated = it.copy(
                state = newState.name,
                errorMessage = error,
                endTime = if (newState == DeploymentState.CONVERGED || newState == DeploymentState.FAILED || newState == DeploymentState.ROLLED_BACK) System.currentTimeMillis() else it.endTime
            )
            deploymentDao.updateDeployment(updated)
        }

        structuredLogger.logEvent(
            TAG,
            "DEPLOYMENT_STATE_CHANGE",
            "Deployment state updated",
            mapOf(
                "deploymentId" to deploymentId,
                "state" to newState.name,
                "error" to (error ?: "none"),
                "cohort" to (record?.cohortId ?: "unknown")
            )
        )
    }

    /**
     * Streams the current cohort-level health to Quasar.
     */
    fun streamCohortHealth(cohortId: String, riskLevel: String, anomalies: List<String>) {
        structuredLogger.logEvent(
            TAG,
            "COHORT_HEALTH_STREAM",
            "Real-time cohort health report",
            mapOf(
                "cohortId" to cohortId,
                "riskLevel" to riskLevel,
                "anomalies" to anomalies
            )
        )
    }

    /**
     * Records an installation failure.
     */
    suspend fun recordFailure(deploymentId: String, error: String) {
        updateState(deploymentId, DeploymentState.FAILED, error)
    }
}
