package com.iips.launcher.reconciliation

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Engine that identifies discrepancies between the expected and actual device states.
 */
@Singleton
class DriftDetectionEngine @Inject constructor() {
    companion object {
        private const val TAG = "DriftDetection"
    }

    /**
     * Detects drift between expected and actual state.
     */
    fun detectDrift(expected: ExpectedDeviceState, actual: ActualDeviceState): List<DriftEvent> {
        val events = mutableListOf<DriftEvent>()

        // 1. Ownership & Admin Drift
        if (!actual.isDeviceOwner) events.add(DriftEvent.OwnershipLost)
        if (!actual.isAdminActive) events.add(DriftEvent.AdminDeactivated)

        // 2. Package Drift
        val missingRequired = expected.requiredPackages - actual.installedPackages
        if (missingRequired.isNotEmpty()) {
            events.add(DriftEvent.MissingRequiredPackages(missingRequired))
        }

        val forbiddenInstalled = expected.forbiddenPackages.intersect(actual.installedPackages)
        if (forbiddenInstalled.isNotEmpty()) {
            events.add(DriftEvent.ForbiddenPackagesInstalled(forbiddenInstalled))
        }

        // 3. Kiosk & UI Drift
        if (expected.isLockTaskRequired && !actual.isLockTaskActive) {
            events.add(DriftEvent.LockTaskDeactivated)
        }
        if (expected.isKioskRequired && !actual.isLauncherDefault) {
            events.add(DriftEvent.LauncherNotDefault)
        }

        // 4. Protection Drift
        if (expected.isSafeBootDisabledRequired && !actual.isSafeBootDisabled) {
            events.add(DriftEvent.ProtectionDisabled("SafeBoot"))
        }
        if (expected.isFactoryResetDisabledRequired && !actual.isFactoryResetDisabled) {
            events.add(DriftEvent.ProtectionDisabled("FactoryReset"))
        }

        // 5. Integrity Drift
        if (actual.integrityLevel.ordinal > expected.minIntegrityLevel.ordinal) {
            events.add(DriftEvent.IntegrityFailure(actual.integrityLevel))
        }

        if (events.isNotEmpty()) {
            Log.w(TAG, "Drift detected: \${events.size} discrepancy events")
        }

        return events
    }
}

/**
 * Sealed class representing specific drift events.
 */
sealed class DriftEvent {
    object OwnershipLost : DriftEvent()
    object AdminDeactivated : DriftEvent()
    data class MissingRequiredPackages(val packages: Set<String>) : DriftEvent()
    data class ForbiddenPackagesInstalled(val packages: Set<String>) : DriftEvent()
    object LockTaskDeactivated : DriftEvent()
    object LauncherNotDefault : DriftEvent()
    data class ProtectionDisabled(val name: String) : DriftEvent()
    data class IntegrityFailure(val level: com.iips.launcher.integrity.IntegrityManager.IntegrityLevel) : DriftEvent()
}
