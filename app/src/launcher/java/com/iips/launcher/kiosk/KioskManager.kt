package com.iips.launcher.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.iips.launcher.policy.DeviceAdminReceiver
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Kiosk mode and LockTask enforcement.
 */
@Singleton
class KioskManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "KioskManager"
    }

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = DeviceAdminReceiver.getComponentName(context)

    fun isKioskEnabled(): Boolean {
        return SecurePreferences.getKioskModeEnabled(context)
    }

    /**
     * Activates Kiosk mode by enabling LockTask for the launcher.
     */
    fun enableKiosk(activity: Activity) {
        Log.i(TAG, "Enabling Kiosk mode")
        
        try {
            if (!DeviceAdminReceiver.isDeviceOwner(context)) {
                Log.e(TAG, "Cannot enable Kiosk: Not Device Owner")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
                activity.startLockTask()
                Log.d(TAG, "LockTask started for \${context.packageName}")
            }
            
            SecurePreferences.setKioskModeEnabled(context, true)
            startEnforcementService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable Kiosk", e)
        }
    }

    /**
     * Deactivates Kiosk mode.
     */
    fun disableKiosk(activity: Activity) {
        Log.i(TAG, "Disabling Kiosk mode")
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                activity.stopLockTask()
                dpm.setLockTaskPackages(admin, emptyArray())
            }
            
            SecurePreferences.setKioskModeEnabled(context, false)
            stopEnforcementService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable Kiosk", e)
        }
    }

    /**
     * Starts the background enforcement service.
     */
    fun startEnforcementService() {
        val intent = Intent(context, ForegroundEnforcementService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * Stops the background enforcement service.
     */
    fun stopEnforcementService() {
        val intent = Intent(context, ForegroundEnforcementService::class.java)
        context.stopService(intent)
    }
}
