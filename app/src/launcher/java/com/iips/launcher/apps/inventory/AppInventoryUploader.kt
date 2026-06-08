package com.iips.launcher.apps.inventory

import android.content.Context
import android.util.Log
import com.iips.launcher.apps.inventory.data.AppInventoryDao
import com.iips.launcher.network.ConfigService
import com.iips.launcher.security.TelemetryHmacSigner
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppInventoryUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appInventoryDao: AppInventoryDao,
    private val configService: ConfigService,
    private val hmacSigner: TelemetryHmacSigner
) {
    companion object {
        private const val TAG = "AppInventoryUploader"
    }

    suspend fun syncInventory() {
        val inventory = appInventoryDao.getAll()
        val token = SecurePreferences.getDeviceToken(context) ?: return
        val deviceId = SecurePreferences.getDeviceId(context) ?: return

        val payload = mapOf(
            "deviceId" to deviceId,
            "timestamp" to System.currentTimeMillis(),
            "apps" to inventory
        )

        val signature = hmacSigner.signPayload(payload.toString(), token, "", "") // Simplified signature for now

        try {
            val response = configService.reportInventory("Bearer $token", signature, payload)
            if (response.isSuccessful) {
                Log.i(TAG, "Inventory synced successfully")
            } else {
                Log.e(TAG, "Inventory sync failed: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inventory sync error", e)
        }
    }
}
