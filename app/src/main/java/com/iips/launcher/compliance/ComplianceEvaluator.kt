package com.iips.launcher.compliance

import android.content.Context
import com.iips.launcher.deviceowner.DeviceOwnerManager
import com.iips.launcher.security.SecurityMonitorManager
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

import com.iips.launcher.integrity.IntegrityManager

/**
 * Evaluates various security and policy factors to calculate a compliance score.
 */
@Singleton
class ComplianceEvaluator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val complianceManager: ComplianceManager,
    private val deviceOwnerManager: DeviceOwnerManager,
    private val securityMonitor: SecurityMonitorManager,
    private val integrityManager: IntegrityManager
) {
    /**
     * Performs a full compliance evaluation.
     */
    fun evaluate() {
        // 1. Check Device Owner
        if (!deviceOwnerManager.isDeviceOwner()) {
            complianceManager.updateState(ComplianceManager.ComplianceState.CRITICAL, "Device Owner lost")
            return
        }

        // 2. Check Root/Tampering
        if (securityMonitor.isRooted()) {
            complianceManager.updateState(ComplianceManager.ComplianceState.QUARANTINED, "Device is rooted")
            return
        }

        // 3. Check Policy Drift
        val lastUpdate = SecurePreferences.getDevicePolicySnapshot(context)?.lastUpdatedAt ?: 0
        val age = System.currentTimeMillis() - lastUpdate
        if (age > 48 * 3600 * 1000) { // 48 hours
            complianceManager.updateState(ComplianceManager.ComplianceState.WARNING, "Policy is severely outdated")
        }

        // 4. Check Play Integrity
        val integrityLevel = integrityManager.getIntegrityLevel()
        if (integrityLevel == com.iips.launcher.integrity.IntegrityManager.IntegrityLevel.NONE) {
            complianceManager.updateState(ComplianceManager.ComplianceState.CRITICAL, "Device fails basic integrity checks")
            return
        } else if (integrityLevel == com.iips.launcher.integrity.IntegrityManager.IntegrityLevel.MEETS_BASIC_INTEGRITY) {
            complianceManager.updateState(ComplianceManager.ComplianceState.WARNING, "Device meets only basic integrity")
            // Not a hard return, as basic integrity might be acceptable for some tenants
        }

        // Default to compliant if no critical issues found
        if (complianceManager.getCurrentState() != ComplianceManager.ComplianceState.WARNING) {
            complianceManager.updateState(ComplianceManager.ComplianceState.COMPLIANT, "All checks passed")
        }
    }
}
