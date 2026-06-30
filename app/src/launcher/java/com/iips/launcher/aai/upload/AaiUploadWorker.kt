package com.iips.launcher.aai.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.util.Log

class AaiUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val batchUploader = BatchUploader(applicationContext)
            val success = batchUploader.uploadPendingBatches()
            if (success) {
                Result.success()
            } else {
                Result.retry() // Retries with exponential backoff via WorkManager
            }
        } catch (e: Exception) {
            Log.e("AaiUploadWorker", "Error uploading AAI batches", e)
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()
            val request = androidx.work.PeriodicWorkRequestBuilder<AaiUploadWorker>(15, java.util.concurrent.TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "AaiUploadWorker",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
