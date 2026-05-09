package com.iips.launcher.selfheal

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Background worker that periodically triggers the self-healing cycle.
 */
@HiltWorker
class SelfHealingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val selfHealingManager: SelfHealingManager
) : Worker(context, workerParams) {

    companion object {
        private const val TAG = "SelfHealingWorker"
        private const val WORK_NAME = "dotoid_self_healing_sync"

        /**
         * Schedules the periodic self-healing worker.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<SelfHealingWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Self-healing worker scheduled")
        }
    }

    override fun doWork(): Result {
        Log.d(TAG, "Starting periodic self-healing task")
        return try {
            selfHealingManager.performHealing()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Self-healing task failed", e)
            Result.retry()
        }
    }
}
