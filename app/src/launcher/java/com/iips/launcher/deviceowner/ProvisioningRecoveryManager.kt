package com.iips.launcher.deviceowner

import android.content.Context
import android.content.Intent
import android.util.Log
import com.iips.launcher.provisioning.ProvisioningBootstrapService
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages recovery of failed or interrupted provisioning sessions.
 */
@Singleton
class ProvisioningRecoveryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceOwnerManager: DeviceOwnerManager
) {
    companion object {
        private const val TAG = "ProvisioningRecovery"
    }

    /**
     * Checks if provisioning needs recovery and restarts the bootstrap service if necessary.
     */
    fun attemptRecovery() {
        val isDO = deviceOwnerManager.isDeviceOwner()
        val isCompleted = SecurePreferences.isProvisioningCompleted(context)
        val hasToken = SecurePreferences.getEnrollmentToken(context) != null

        Log.d(TAG, "Checking recovery: isDO=$isDO, isCompleted=$isCompleted, hasToken=$hasToken")

        if (isDO && !isCompleted && hasToken) {
            Log.i(TAG, "Interrupted provisioning detected. Restarting bootstrap service...")
            val intent = Intent(context, ProvisioningBootstrapService::class.java)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart bootstrap service", e)
            }
        }
    }
}
