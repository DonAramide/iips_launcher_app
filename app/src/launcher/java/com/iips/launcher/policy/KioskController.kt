package com.iips.launcher.policy

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import com.iips.launcher.policy.DeviceController
import com.iips.launcher.storage.SecurePreferences

object KioskController {
    private const val TAG = "KioskController"

    data class SecurityGateDecision(
        val apply: Boolean,
        val reason: String,
        val deviceOwner: Boolean,
        val registered: Boolean,
        val enrollmentComplete: Boolean,
        val policyAvailable: Boolean,
        val state: String
    )

    /**
     * Existing authoritative state:
     * - registered = device_id + access_token ([SecurePreferences.isRegistered])
     * - enrollment complete = registered AND state ACTIVE/LOCKED
     * - policy available = persisted MDM [DevicePolicySnapshot]
     */
    fun evaluateSecurityGate(context: Context): SecurityGateDecision {
        val deviceOwner = DeviceAdminReceiver.isDeviceOwner(context)
        val registered = SecurePreferences.isRegistered(context)
        val state = SecurePreferences.getDeviceState(context)
        val enrollmentComplete = SecurePreferences.isEnrollmentComplete(context)
        val policyAvailable = SecurePreferences.hasValidPolicySnapshot(context)
        val reason = when {
            !registered || !enrollmentComplete -> "SKIPPED_REASON_NOT_ENROLLED"
            !policyAvailable -> "SKIPPED_REASON_NO_POLICY"
            else -> "APPLY"
        }
        val apply = enrollmentComplete && policyAvailable
        Log.i(
            TAG,
            "DeviceOwner=$deviceOwner EnrollmentComplete=$enrollmentComplete " +
                "Registered=$registered PolicyAvailable=$policyAvailable " +
                "state=$state KioskApply=$reason"
        )
        return SecurityGateDecision(
            apply = apply,
            reason = reason,
            deviceOwner = deviceOwner,
            registered = registered,
            enrollmentComplete = enrollmentComplete,
            policyAvailable = policyAvailable,
            state = state
        )
    }

    /**
     * Reads the current policy from SecurePreferences and applies its restrictions via DeviceController.
     * Device Owner without enrollment + a valid policy snapshot must not lock the device.
     */
    fun applyPolicy(context: Context) {
        val decision = evaluateSecurityGate(context)
        if (!decision.apply) {
            Log.i(TAG, "applyPolicy skipped: ${decision.reason}")
            releasePrematureLockdown(context)
            return
        }

        val snapshot = SecurePreferences.getDevicePolicySnapshot(context)
        if (snapshot == null) {
            Log.i(TAG, "applyPolicy skipped: SKIPPED_REASON_NO_POLICY")
            releasePrematureLockdown(context)
            return
        }

        val kioskEnabled = SecurePreferences.getKioskModeEnabled(context)
        val settingsLocked = SecurePreferences.isSettingsLocked(context)

        var isExpired = false
        val age = System.currentTimeMillis() - snapshot.lastUpdatedAt
        if (age >= 2 * snapshot.maxPolicyAge) {
            isExpired = true
            Log.w(TAG, "MDM Policy EXPIRED. Forcing Lockdown Mode.")
        }

        Log.i(TAG, "Applying Kiosk State: Enabled=$kioskEnabled, SettingsLocked=$settingsLocked (Expired=$isExpired)")

        if (isExpired || kioskEnabled) {
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
     * Helper to resume Lock Task mode if enrollment is complete and policy dictates.
     */
    fun resumeLockTask(activity: Activity) {
        val decision = evaluateSecurityGate(activity)
        if (!decision.apply) {
            Log.i(TAG, "resumeLockTask skipped: ${decision.reason}")
            return
        }

        val kioskEnabled = SecurePreferences.getKioskModeEnabled(activity)
        val snapshot = SecurePreferences.getDevicePolicySnapshot(activity)
        val isExpired = snapshot?.let {
            (System.currentTimeMillis() - it.lastUpdatedAt) >= 2 * it.maxPolicyAge
        } ?: false

        if (isExpired || kioskEnabled) {
            DeviceController.startLockTask(activity)
        }
    }

    private fun releasePrematureLockdown(context: Context) {
        DeviceController.disableLockTaskMode(context)
        if (context is Activity) {
            DeviceController.stopLockTask(context)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            DeviceController.blockSettingsAccess(context, false)
        }
    }
}
