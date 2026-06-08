package com.iips.launcher.deployment.health

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calculates the potential impact of a rollout failure on the local device.
 * Used to report risk levels to Quasar for fleet-wide blast radius containment.
 */
@Singleton
class BlastRadiusEstimator @Inject constructor(
    private val healthService: RolloutHealthService
) {
    enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

    /**
     * Estimates the current deployment risk for this device.
     */
    fun estimateLocalRisk(): RiskLevel {
        val report = healthService.getHealthReport()
        
        var riskScore = 0
        if (report.crashSpike) riskScore += 50
        if (report.kioskFailure) riskScore += 30
        if (report.batteryAnomaly) riskScore += 10
        if (report.telemetryFailure) riskScore += 20
        
        return when {
            riskScore >= 70 -> RiskLevel.CRITICAL
            riskScore >= 40 -> RiskLevel.HIGH
            riskScore >= 20 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }
}
