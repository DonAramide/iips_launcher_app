package com.iips.launcher.deviceowner

import android.content.Context
import android.util.Log
import com.iips.launcher.storage.SecurePreferences
import com.iips.launcher.core.StructuredLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level verifier that integrates with telemetry and incident reporting.
 */
@Singleton
class DeviceOwnerVerifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceOwnerManager: DeviceOwnerManager,
    private val structuredLogger: StructuredLogger
) {
    companion object {
        private const val TAG = "DeviceOwnerVerifier"
    }

    /**
     * Performs a hardened verification of device ownership.
     * If ownership is lost and the device was previously provisioned, it logs a critical error.
     */
    fun performVerification() {
        val status = deviceOwnerManager.verifyOwnership()
        val wasProvisioned = SecurePreferences.isProvisioningCompleted(context)

        if (!status.isDeviceOwner) {
            if (wasProvisioned) {
                Log.e(TAG, "CRITICAL: Device Owner status LOST after provisioning!")
                structuredLogger.logIncident(
                    TAG,
                    "DEVICE_OWNER_LOST",
                    "Device Owner status was lost unexpectedly after successful provisioning.",
                    fatal = true
                )
            } else {
                Log.w(TAG, "App is not Device Owner. Provisioning may be required.")
            }
        } else {
            Log.i(TAG, "Device Owner status verified: COMPLIANT")
        }
        
        if (!status.isAdminActive) {
            Log.e(TAG, "CRITICAL: Device Admin is NOT active!")
        }
    }
}
