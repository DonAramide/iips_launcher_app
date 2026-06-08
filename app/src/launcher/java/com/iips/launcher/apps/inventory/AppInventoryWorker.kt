package com.iips.launcher.apps.inventory

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.iips.launcher.apps.inventory.data.AppInventoryDao
import com.iips.launcher.storage.SecurePreferences
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
    private val appInventoryDao: AppInventoryDao
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "AppInventoryWorker"
        private const val WORK_NAME = "com.iips.launcher.work.APP_INVENTORY_SYNC"

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
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting app inventory scan...")
            val inventory = scanner.scanAllApps()
            appInventoryDao.insertAll(inventory)
            
            // In a real implementation, we would call an uploader here.
            // uploader.syncInventory(inventory)
            
            Log.d(TAG, "Inventory scan complete. Found ${inventory.size} apps.")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Inventory worker failed", e)
            Result.retry()
        }
    }
}
