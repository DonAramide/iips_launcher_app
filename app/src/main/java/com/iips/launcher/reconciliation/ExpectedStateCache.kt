package com.iips.launcher.reconciliation

import com.iips.launcher.integrity.IntegrityManager
import com.iips.launcher.policy.PolicyRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the expected state of the device based on active policies.
 */
@Singleton
class ExpectedStateCache @Inject constructor(
    private val policyRepository: PolicyRepository
) {
    /**
     * Resolves the expected state from the current policy snapshot.
     */
    fun getExpectedState(): ExpectedDeviceState? {
        val snapshot = policyRepository.currentPolicy.value ?: return null
        
        return ExpectedDeviceState(
            requiredPackages = snapshot.requiredApps.toSet(),
            forbiddenPackages = snapshot.forbiddenApps.toSet(),
            isKioskRequired = snapshot.kioskMode,
            isLockTaskRequired = snapshot.lockTaskMode,
            isStatusBarDisabledRequired = snapshot.statusBarDisabled,
            isSafeBootDisabledRequired = snapshot.safeBootDisabled,
            isFactoryResetDisabledRequired = snapshot.factoryResetDisabled,
            minIntegrityLevel = IntegrityManager.IntegrityLevel.MEETS_DEVICE_INTEGRITY
        )
    }
}

/**
 * Data class representing the expected state of the device.
 */
data class ExpectedDeviceState(
    val requiredPackages: Set<String>,
    val forbiddenPackages: Set<String>,
    val isKioskRequired: Boolean,
    val isLockTaskRequired: Boolean,
    val isStatusBarDisabledRequired: Boolean,
    val isSafeBootDisabledRequired: Boolean,
    val isFactoryResetDisabledRequired: Boolean,
    val minIntegrityLevel: IntegrityManager.IntegrityLevel
)
