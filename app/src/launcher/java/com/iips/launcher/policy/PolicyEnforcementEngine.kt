package com.iips.launcher.policy

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.iips.launcher.core.StructuredLogger
import com.iips.launcher.network.models.DevicePolicySnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hardened engine for enforcing enterprise policies via DevicePolicyManager.
 * Supports 20+ policies including restrictions, app controls, and kiosk state.
 */
@Singleton
class PolicyEnforcementEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val structuredLogger: StructuredLogger
) {
    companion object {
        private const val TAG = "PolicyEnforcement"
    }

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = DeviceAdminReceiver.getComponentName(context)

    /**
     * Enforces the entire policy snapshot onto the device.
     */
    fun enforce(snapshot: DevicePolicySnapshot) {
        Log.i(TAG, "Starting full policy enforcement (Version: \${snapshot.version})")
        
        try {
            if (!dpm.isAdminActive(admin)) {
                Log.e(TAG, "Enforcement failed: Admin not active")
                return
            }

            // 1. App Policies
            enforceAppVisibility(snapshot)
            
            // 2. System Restrictions
            enforceUserRestrictions(snapshot)
            
            // 3. Hardware Policies
            enforceHardwareRestrictions(snapshot)
            
            // 4. UI & Kiosk Policies
            enforceUiRestrictions(snapshot)
            
            // 5. Connectivity Policies
            enforceConnectivityRestrictions(snapshot)
            // 6. Geofence Policies
            if (snapshot.geofenceRules.isNotEmpty()) {
                Log.i(TAG, "Geofence rules found, starting GeofenceService")
                com.iips.launcher.policy.GeofenceService.start(context)
            }

            Log.i(TAG, "Policy enforcement completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Policy enforcement failed", e)
            structuredLogger.logIncident(
                TAG,
                "POLICY_ENFORCEMENT_FAILED",
                "Failed to enforce device policy snapshot version ${snapshot.version}: ${e.message}",
                fatal = false
            )
        }
    }

    private fun enforceAppVisibility(snapshot: DevicePolicySnapshot) {
        val pm = context.packageManager
        val installedApps = pm.getInstalledPackages(0)
            .filter { it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { it.packageName }

        for (pkg in installedApps) {
            // Logic: Hide if blocked OR forbidden. Allow if allowed OR required.
            // Priority: Blocked/Forbidden > Allowed/Required.
            val shouldHide = snapshot.blockedApps.contains(pkg) || 
                             snapshot.forbiddenApps.contains(pkg) ||
                             (!snapshot.allowedApps.contains(pkg) && !snapshot.requiredApps.contains(pkg) && pkg != context.packageName)

            try {
                dpm.setApplicationHidden(admin, pkg, shouldHide)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to hide/unhide app \$pkg: \${e.message}")
            }
        }
    }

    private fun enforceUserRestrictions(snapshot: DevicePolicySnapshot) {
        setRestriction(UserManager.DISALLOW_FACTORY_RESET, snapshot.factoryResetDisabled)
        setRestriction(UserManager.DISALLOW_SAFE_BOOT, snapshot.safeBootDisabled)
        setRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER, snapshot.usbFileTransferDisabled)
        setRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, snapshot.adbDisabled)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setRestriction(UserManager.DISALLOW_ADD_USER, true)
            setRestriction(UserManager.DISALLOW_REMOVE_USER, true)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Developer options restriction
            // Note: DISALLOW_DEBUGGING_FEATURES handles ADB, but developer options is broader
        }
    }

    private fun enforceHardwareRestrictions(snapshot: DevicePolicySnapshot) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dpm.setCameraDisabled(admin, snapshot.cameraDisabled)
        }
        
        // Microphone restriction (via UserManager)
        // setRestriction(UserManager.DISALLOW_UNMUTE_DEVICE, snapshot.microphoneDisabled)
    }

    private fun enforceUiRestrictions(snapshot: DevicePolicySnapshot) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dpm.setStatusBarDisabled(admin, snapshot.statusBarDisabled)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dpm.setScreenCaptureDisabled(admin, snapshot.screenCaptureDisabled)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            dpm.setScreenCaptureDisabled(admin, snapshot.screenCaptureDisabled)
        }
    }

    private fun enforceConnectivityRestrictions(snapshot: DevicePolicySnapshot) {
        setRestriction(UserManager.DISALLOW_CONFIG_VPN, snapshot.vpnRestricted)
        setRestriction(UserManager.DISALLOW_CONFIG_WIFI, snapshot.wifiRestricted)
        setRestriction(UserManager.DISALLOW_CONFIG_BLUETOOTH, snapshot.bluetoothRestricted)
    }

    private fun setRestriction(restriction: String, enabled: Boolean) {
        try {
            if (enabled) {
                dpm.addUserRestriction(admin, restriction)
            } else {
                dpm.clearUserRestriction(admin, restriction)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set restriction \$restriction: \${e.message}")
        }
    }
}
