package com.iips.launcher.apps.inventory

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.iips.launcher.apps.inventory.data.AppInventoryDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@HiltWorker
class AppInventoryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val scanner: InstalledAppsScanner,
    private val appInventoryDao: AppInventoryDao,
    private val uploader: AppInventoryUploader
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "AppInventoryWorker"
        private const val WORK_NAME = "com.iips.launcher.work.APP_INVENTORY_SYNC"
        private const val ONE_TIME_WORK_NAME = "com.iips.launcher.work.APP_INVENTORY_SYNC_NOW"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<AppInventoryWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
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

            val request = OneTimeWorkRequestBuilder<AppInventoryWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting app inventory scan...")
            val inventory = scanner.scanAllApps()
            appInventoryDao.insertAll(inventory)
            
            uploader.syncInventory()
            
            Log.d(TAG, "Inventory scan and upload complete. Found ${inventory.size} apps.")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Inventory worker failed", e)
            Result.retry()
        }
    }
}
