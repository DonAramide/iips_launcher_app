package com.iips.launcher.deployment.health

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Analyzes local health signals to determine the risk level of an ongoing deployment.
 */
@Singleton
class DeploymentRiskAnalyzer @Inject constructor(
    private val healthService: RolloutHealthService,
    private val blastRadiusEstimator: BlastRadiusEstimator
) {
    companion object {
        private const val TAG = "DeploymentRisk"
    }

    /**
     * Determines if the risk is high enough to trigger a rollback.
     */
    fun shouldTriggerRollback(): Boolean {
        val report = healthService.getHealthReport()
        val risk = blastRadiusEstimator.estimateLocalRisk()
        
        Log.d(TAG, "Analyzing deployment risk: \$risk, Unhealthy: \${report.isUnhealthy()}")

        // Rollback if critical risk OR specific high-impact failures
        return risk == BlastRadiusEstimator.RiskLevel.CRITICAL || 
               report.crashSpike || 
               report.kioskFailure
    }
}
