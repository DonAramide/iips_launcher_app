package com.iips.launcher.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.iips.launcher.policy.PolicyManager
import com.iips.launcher.storage.SecurePreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Background worker that synchronizes enterprise policies with the backend.
 */
@HiltWorker
class PolicySyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val policyManager: PolicyManager
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "PolicySyncWorker"
        private const val WORK_NAME = "com.iips.launcher.work.POLICY_SYNC"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<PolicySyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<PolicySyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }

    override suspend fun doWork(): Result {
        if (!SecurePreferences.isRegistered(applicationContext)) {
            Log.w(TAG, "Device not registered, skipping policy sync")
            return Result.retry()
        }

        return try {
            policyManager.syncAndEnforce()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Policy sync failed", e)
            Result.retry()
        }
    }
}
