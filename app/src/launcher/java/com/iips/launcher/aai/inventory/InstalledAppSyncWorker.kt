package com.iips.launcher.aai.inventory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.util.Log

class InstalledAppSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val scanner = InstalledApplicationScanner(applicationContext)
            val apps = scanner.scanInstalledApps()
            Log.d("AaiSyncWorker", "Scanned \${apps.size} installed applications.")
            
            // Sync to Quasar logic omitted for Phase 2 constraint
            
            Result.success()
        } catch (e: Exception) {
            Log.e("AaiSyncWorker", "Failed to sync installed apps", e)
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()
            val request = androidx.work.PeriodicWorkRequestBuilder<InstalledAppSyncWorker>(12, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "InstalledAppSyncWorker",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
