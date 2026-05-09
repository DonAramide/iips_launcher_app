package com.iips.launcher.policy

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import com.iips.launcher.policy.DeviceController
import com.iips.launcher.storage.SecurePreferences

object KioskController {
    private const val TAG = "KioskController"

    /**
     * Reads the current policy from SecurePreferences and applies its restrictions via DeviceController.
     */
    fun applyPolicy(context: Context) {
        val snapshot = SecurePreferences.getDevicePolicySnapshot(context)
        val kioskEnabled = SecurePreferences.getKioskModeEnabled(context)
        val settingsLocked = SecurePreferences.isSettingsLocked(context)
        
        // 1. Check for Expired Policy (Lockdown)
        var isExpired = false
        if (snapshot != null) {
            val age = System.currentTimeMillis() - snapshot.lastUpdatedAt
            if (age >= 2 * snapshot.maxPolicyAge) { // 2x maxAge = Expired
                isExpired = true
                Log.w(TAG, "MDM Policy EXPIRED. Forcing Lockdown Mode.")
            }
        } else {
            Log.w(TAG, "No MDM Policy found. Defaulting to Lockdown.")
            isExpired = true
        }

        Log.d(TAG, "Applying Kiosk State: Enabled=$kioskEnabled, SettingsLocked=$settingsLocked (Expired=$isExpired)")

        if (isExpired || kioskEnabled) {
            // Enable kiosk mode
            SecurePreferences.setLockdownEnabled(context, true)
            DeviceController.enableLockTaskMode(context)
            if (context is Activity) DeviceController.startLockTask(context)
            
            if (settingsLocked || isExpired) {
                DeviceController.enableComprehensiveSecurity(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    DeviceController.preventForceStop(context)
                }
            }
        } else {
            // Disable kiosk mode completely
            SecurePreferences.setLockdownEnabled(context, false)
            DeviceController.disableLockTaskMode(context)
            if (context is Activity) {
                DeviceController.stopLockTask(context)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                DeviceController.blockSettingsAccess(context, false)
            }
        }
    }

    /**
     * Helper to temporarily pause Lock Task mode.
     */
    fun pauseLockTask(activity: Activity) {
        DeviceController.stopLockTask(activity)
    }

    /**
     * Helper to resume Lock Task mode if policy dictates.
     */
    fun resumeLockTask(activity: Activity) {
        val kioskEnabled = SecurePreferences.getKioskModeEnabled(activity)
        val snapshot = SecurePreferences.getDevicePolicySnapshot(activity)
        
        val isExpired = snapshot?.let { (System.currentTimeMillis() - it.lastUpdatedAt) >= 2 * it.maxPolicyAge } ?: true

        if (isExpired || kioskEnabled) {
            DeviceController.startLockTask(activity)
        }
    }
}
