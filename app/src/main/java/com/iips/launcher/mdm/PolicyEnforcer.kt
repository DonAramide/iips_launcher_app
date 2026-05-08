package com.iips.launcher.mdm

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.iips.launcher.data.AppDatabase
import com.iips.launcher.device.DeviceAdminReceiver
import android.os.UserManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Compares the backend App Policy with installed apps and enforces the state.
 */
object PolicyEnforcer {
    private const val TAG = "PolicyEnforcer"

    /**
     * Enforcement Modes based on policy age.
     */
    enum class EnforcementLevel {
        FRESH,    // Normal enforcement
        STALE,    // Restrict risky apps
        EXPIRED   // Lockdown / Strict mode
    }

    suspend fun enforcePolicy(context: Context, snapshot: com.iips.launcher.data.DevicePolicySnapshot) = withContext(Dispatchers.IO) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = DeviceAdminReceiver.getComponentName(context)
        val packageManager = context.packageManager

        // 1. Determine Enforcement Level
        val age = System.currentTimeMillis() - snapshot.lastUpdatedAt
        val level = when {
            age < snapshot.maxPolicyAge -> EnforcementLevel.FRESH
            age < 2 * snapshot.maxPolicyAge -> EnforcementLevel.STALE
            else -> EnforcementLevel.EXPIRED
        }

        Log.d(TAG, "Enforcing policy at level: $level (Age: ${age/1000}s, Max: ${snapshot.maxPolicyAge/1000}s)")

        // 2. Get all installed non-system apps
        val installedApps = packageManager.getInstalledPackages(0)
            .filter { it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { it.packageName }

        // 3. Apply level-specific rules
        for (pkg in installedApps) {
            val shouldHide = when (level) {
                EnforcementLevel.FRESH -> {
                    // Standard logic: hide if explicitly blocked OR not explicitly allowed
                    snapshot.blockedApps.contains(pkg) || !snapshot.allowedApps.contains(pkg)
                }
                EnforcementLevel.STALE -> {
                    // Degraded: allow normal apps but block "risky" ones (e.g. browsers, stores)
                    val riskyApps = listOf("com.android.vending", "com.android.chrome", "org.mozilla.firefox")
                    snapshot.blockedApps.contains(pkg) || riskyApps.contains(pkg)
                }
                EnforcementLevel.EXPIRED -> {
                    // Lockdown: hide EVERYTHING except the launcher itself
                    pkg != context.packageName
                }
            }

            try {
                dpm.setApplicationHidden(adminComponent, pkg, shouldHide)
                if (shouldHide) Log.d(TAG, "Hidden app due to $level policy: $pkg")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set hidden state for $pkg", e)
            }
        }

        // 4. Handle Policy Flags (Kiosk, Settings Lock, etc.)
        try {
            // Apply Kiosk mode if explicitly enabled in policy
            if (snapshot.kioskMode) {
                com.iips.launcher.mdm.KioskController.applyPolicy(context)
            }
            
            // Apply settings restriction if enabled
            if (snapshot.settingsLock) {
                // Settings lock is primarily handled by hiding settings app in DeviceController
                com.iips.launcher.utils.DeviceController.blockSettingsAccess(context, true)
            } else {
                com.iips.launcher.utils.DeviceController.blockSettingsAccess(context, false)
            }
            
            // Always ensure factory reset is disabled in enterprise mode
            dpm.addUserRestriction(adminComponent, android.os.UserManager.DISALLOW_FACTORY_RESET)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply policy flags", e)
        }
    }
}
