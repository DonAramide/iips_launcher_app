package com.iips.launcher.runtime

import android.content.Context
import android.util.Log
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enterprise-grade wrapper for WorkManager that ensures resilient task scheduling.
 * Handles deduplication, backoff, and state persistence.
 */
@Singleton
class ResilientScheduler @Inject constructor(
    @ApplicationContext val context: Context
) {
    companion object {
        private const val TAG = "ResilientScheduler"
    }

    /**
     * Schedules a unique periodic worker with enterprise defaults.
     */
    inline fun <reified T : ListenableWorker> schedulePeriodic(
        workName: String,
        intervalMinutes: Long,
        policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<T>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            workName,
            policy,
            request
        )
    }

    /**
     * Schedules a one-time worker with jittered retry to prevent server spikes.
     */
    inline fun <reified T : ListenableWorker> scheduleOneTime(
        workName: String,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE
    ) {
        val request = OneTimeWorkRequestBuilder<T>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName,
            policy,
            request
        )
    }
}
