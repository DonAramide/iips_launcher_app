package com.iips.launcher.workers

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.*
import com.iips.launcher.provisioning.ProvisioningBootstrapService
import com.iips.launcher.storage.SecurePreferences
import java.util.concurrent.TimeUnit

/**
 * WorkManager retry worker for [ProvisioningBootstrapService].
 *
 * Scheduled when inline exponential backoff in the bootstrap service is exhausted.
 * Requires an active network connection before attempting; WorkManager enforces
 * this via [NetworkType.CONNECTED] constraint.
 *
 * Self-cancels if the device is already successfully enrolled.
 *
 * Retry policy:
 *  - Initial delay: 30 seconds
 *  - WorkManager back-off: EXPONENTIAL, starting at 1 minute
 *  - Max attempts guarded by [MAX_WORKER_RETRIES]
 */
class EnrollmentRetryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG              = "EnrollmentRetryWorker"
        private const val WORK_NAME        = "dotoid_enrollment_retry"
        private const val MAX_WORKER_RETRIES = 10

        /**
         * Schedule a one-time retry attempt via WorkManager.
         * Safe to call multiple times — uses [ExistingWorkPolicy.REPLACE]
         * so duplicate schedules don't accumulate.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<EnrollmentRetryWorker>()
                .setConstraints(constraints)
                .setInitialDelay(30, TimeUnit.SECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )

            Log.i(TAG, "Enrollment retry scheduled via WorkManager")
        }

        /** Cancel any pending retry work (call after successful enrollment). */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Enrollment retry work cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "EnrollmentRetryWorker — doWork() attempt #${runAttemptCount + 1}")

        // Guard: already enrolled?
        if (SecurePreferences.isProvisioningCompleted(applicationContext) &&
            SecurePreferences.isRegistered(applicationContext)) {
            Log.i(TAG, "Device already enrolled — cancelling retry work")
            cancel(applicationContext)
            return Result.success()
        }

        // Guard: no token means there's nothing we can do
        if (SecurePreferences.getEnrollmentToken(applicationContext) == null) {
            Log.e(TAG, "No enrollment token available — cannot retry")
            return Result.failure(
                workDataOf("error" to "No enrollment token stored")
            )
        }

        // Guard: max retries exceeded
        if (runAttemptCount >= MAX_WORKER_RETRIES) {
            Log.e(TAG, "Max WorkManager retries ($MAX_WORKER_RETRIES) exceeded — giving up")
            return Result.failure(
                workDataOf("error" to "Max retry attempts exceeded")
            )
        }

        // Delegate back to ProvisioningBootstrapService for the actual enrollment attempt
        return try {
            val serviceIntent = Intent(applicationContext, ProvisioningBootstrapService::class.java).apply {
                putExtra(ProvisioningBootstrapService.EXTRA_IS_RETRY, true)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }

            Log.i(TAG, "ProvisioningBootstrapService restarted for retry")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ProvisioningBootstrapService: ${e.message}")
            // Return retry so WorkManager schedules another attempt
            Result.retry()
        }
    }
}
