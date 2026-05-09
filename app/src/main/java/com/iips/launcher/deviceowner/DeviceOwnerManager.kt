package com.iips.launcher.deviceowner

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log
import com.iips.launcher.policy.DeviceAdminReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages and verifies the Device Owner status of the application.
 * Provides hardened verification and state monitoring.
 */
@Singleton
class DeviceOwnerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DeviceOwnerManager"
    }

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /**
     * Checks if the app is currently the Device Owner.
     */
    fun isDeviceOwner(): Boolean {
        val isDO = dpm.isDeviceOwnerApp(context.packageName)
        Log.d(TAG, "isDeviceOwner check: $isDO")
        return isDO
    }

    /**
     * Verifies ownership and returns detailed status.
     */
    fun verifyOwnership(): OwnershipStatus {
        val isDO = isDeviceOwner()
        val isAdminActive = dpm.isAdminActive(DeviceAdminReceiver.getComponentName(context))
        
        return OwnershipStatus(
            isDeviceOwner = isDO,
            isAdminActive = isAdminActive,
            packageName = context.packageName
        )
    }

    /**
     * Detailed ownership status data class.
     */
    data class OwnershipStatus(
        val isDeviceOwner: Boolean,
        val isAdminActive: Boolean,
        val packageName: String
    )
}
