package com.iips.launcher.workers

import android.content.Context
import android.util.Log
import androidx.work.*
import com.iips.launcher.auth.GuardDeviceSyncRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

class GuardDeviceSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GuardDeviceSyncWorkerEntryPoint {
        fun guardDeviceSyncRepository(): GuardDeviceSyncRepository
    }

    override suspend fun doWork(): Result {
        Log.i("GuardDeviceSyncWorker", "Starting background sync work...")
        
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            GuardDeviceSyncWorkerEntryPoint::class.java
        )
        val repository = entryPoint.guardDeviceSyncRepository()

        return try {
            val result = repository.syncDevices()
            if (result.isSuccess) {
                Log.i("GuardDeviceSyncWorker", "Background sync completed successfully")
                Result.success()
            } else {
                Log.e("GuardDeviceSyncWorker", "Background sync failed: ${result.exceptionOrNull()?.message}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("GuardDeviceSyncWorker", "Exception in background sync: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val SYNC_WORK_NAME = "guard_device_sync_work"

        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<GuardDeviceSyncWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
            Log.i("GuardDeviceSyncWorker", "Periodic background device sync scheduled (15 mins)")
        }
        
        fun cancelSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
        }
    }
}
