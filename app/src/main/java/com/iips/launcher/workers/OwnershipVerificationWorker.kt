package com.iips.launcher.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.iips.launcher.deviceowner.DeviceOwnerVerifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for periodic device ownership verification.
 */
@HiltWorker
class OwnershipVerificationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val verifier: DeviceOwnerVerifier
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            verifier.performVerification()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "OwnershipVerificationWork"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<OwnershipVerificationWorker>(
                1, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
