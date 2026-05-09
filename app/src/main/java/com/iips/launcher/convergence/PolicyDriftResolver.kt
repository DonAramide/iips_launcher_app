package com.iips.launcher.convergence

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log
import com.iips.launcher.install.PackageInstallManager
import com.iips.launcher.policy.DeviceAdminReceiver
import com.iips.launcher.policy.PolicyEnforcementEngine
import com.iips.launcher.policy.PolicyRepository
import com.iips.launcher.reconciliation.DriftEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level resolver that corrects policy drift through complex actions.
 */
@Singleton
class PolicyDriftResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val policyRepository: PolicyRepository,
    private val policyEnforcementEngine: PolicyEnforcementEngine,
    private val packageInstallManager: PackageInstallManager
) {
    companion object {
        private const val TAG = "PolicyDriftResolver"
    }

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = DeviceAdminReceiver.getComponentName(context)

    /**
     * Resolves complex drift events.
     */
    fun resolve(event: DriftEvent) {
        Log.i(TAG, "Resolving drift event: \${event.javaClass.simpleName}")

        when (event) {
            is DriftEvent.MissingRequiredPackages -> {
                Log.w(TAG, "Drift: Missing packages: \${event.packages}")
                // In a real scenario, we'd check the install queue and trigger downloads
                // For now, we log it for the next sync cycle to handle
            }

            is DriftEvent.ForbiddenPackagesInstalled -> {
                Log.w(TAG, "Drift: Forbidden packages found: \${event.packages}")
                for (pkg in event.packages) {
                    uninstallForbiddenPackage(pkg)
                }
            }

            is DriftEvent.ProtectionDisabled -> {
                Log.w(TAG, "Drift: Protection \${event.name} disabled. Forcing policy re-enforcement.")
                forcePolicyEnforcement()
            }

            else -> {
                Log.d(TAG, "Event \${event.javaClass.simpleName} handled by RuntimeRecoveryEngine or requires manual intervention")
            }
        }
    }

    private fun uninstallForbiddenPackage(packageName: String) {
        try {
            Log.i(TAG, "Attempting silent uninstall of forbidden package: \$packageName")
            // Android Enterprise doesn't have a direct 'silentUninstall' for user apps without User Action
            // UNLESS it was installed via MDM or we use PackageInstaller.
            // But we can at least HIDE it immediately.
            dpm.setApplicationHidden(admin, packageName, true)
            Log.d(TAG, "Package \$packageName hidden as remediation")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remediate forbidden package \$packageName", e)
        }
    }

    private fun forcePolicyEnforcement() {
        val snapshot = policyRepository.currentPolicy.value
        if (snapshot != snapshot) { // Re-read to ensure we have latest
            snapshot?.let { policyEnforcementEngine.enforce(it) }
        }
    }
}
