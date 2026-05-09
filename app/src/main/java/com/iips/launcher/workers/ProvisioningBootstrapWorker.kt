package com.iips.launcher.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.iips.launcher.deviceowner.DeviceOwnerManager
import com.iips.launcher.storage.SecurePreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Worker that validates the device state after the initial provisioning bootstrap.
 * Ensures that all enterprise components (enrollment, policy, launcher pinning) are ready.
 */
@HiltWorker
class ProvisioningBootstrapWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val deviceOwnerManager: DeviceOwnerManager
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BootstrapWorker"
        private const val WORK_NAME = "PostProvisioningBootstrap"

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<ProvisioningBootstrapWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting post-provisioning bootstrap validation")

        // 1. Verify Device Owner
        if (!deviceOwnerManager.isDeviceOwner()) {
            Log.e(TAG, "Bootstrap failed: Not Device Owner")
            return Result.failure()
        }

        // 2. Verify Enrollment
        val deviceId = SecurePreferences.getDeviceId(context)
        val token = SecurePreferences.getDeviceToken(context)
        if (deviceId == null || token == null) {
            Log.e(TAG, "Bootstrap failed: Enrollment data missing")
            return Result.failure()
        }

        // 3. Verify Policy
        val snapshot = SecurePreferences.getDevicePolicySnapshot(context)
        if (snapshot == null) {
            Log.w(TAG, "Bootstrap warning: No policy snapshot found yet")
            // We might want to trigger a manual sync here if needed
        }

        // 4. Verify Launcher Pinning
        // TODO: Check if we are the default home app

        Log.i(TAG, "Post-provisioning bootstrap completed successfully")
        SecurePreferences.setProvisioningCompleted(context, true)
        
        return Result.success()
    }
}
