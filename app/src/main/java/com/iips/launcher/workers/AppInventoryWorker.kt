package com.iips.launcher.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.iips.launcher.apps.inventory.InstalledAppsScanner
import com.iips.launcher.data.AppDatabase
import com.iips.launcher.network.ConfigService
import com.iips.launcher.security.TelemetryHmacSigner
import com.iips.launcher.storage.SecurePreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class AppInventoryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val scanner: InstalledAppsScanner,
    private val database: AppDatabase,
    private val configService: ConfigService,
    private val hmacSigner: TelemetryHmacSigner
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "AppInventoryWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting periodic app inventory scan")
            val inventory = scanner.scanAllApps()
            
            // Save to local database
            database.appInventoryDao().insertAll(inventory)
            
            // Report to backend (Full Snapshot for now, Delta reporting in next phase)
            val deviceId = SecurePreferences.getDeviceId(applicationContext) ?: return@withContext Result.failure()
            val tenantId = SecurePreferences.getTenantId(applicationContext) ?: "default"
            
            val payload = mapOf(
                "deviceId" to deviceId,
                "tenantId" to tenantId,
                "timestamp" to System.currentTimeMillis(),
                "inventory" to inventory
            )
            
            // Sign and Send
            // val signature = hmacSigner.sign(payload.toString())
            // configService.reportInventory(payload, signature)
            
            Log.i(TAG, "App inventory scan completed and saved. Found ${inventory.size} apps.")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Inventory worker failed", e)
            Result.retry()
        }
    }
}
