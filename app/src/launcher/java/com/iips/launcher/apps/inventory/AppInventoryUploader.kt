package com.iips.launcher.apps.inventory

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.iips.launcher.apps.inventory.data.AppInventoryDao
import com.iips.launcher.network.ConfigService
import com.iips.launcher.security.TelemetryHmacSigner
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private val gson = Gson()

    suspend fun syncInventory(): Boolean = withContext(Dispatchers.IO) {
        val inventory = appInventoryDao.getAll()
        val token = SecurePreferences.getDeviceToken(context)
        if (token.isNullOrEmpty()) {
            Log.w(TAG, "Inventory sync skipped: Device token not available.")
            return@withContext false
        }
        val deviceId = SecurePreferences.getDeviceId(context) ?: "UNKNOWN_DEVICE"
        val tenantId = SecurePreferences.getTenantId(context) ?: "default"

        val formattedApps = inventory.map { app ->
            mapOf(
                "packageName" to app.packageName,
                "package_name" to app.packageName,
                "appName" to app.appName,
                "app_name" to app.appName,
                "name" to app.appName,
                "label" to app.appName,
                "versionName" to app.versionName,
                "version_name" to app.versionName,
                "version" to app.versionName,
                "versionCode" to app.versionCode,
                "version_code" to app.versionCode,
                "isSystemApp" to app.isSystemApp,
                "is_system_app" to app.isSystemApp,
                "systemApp" to app.isSystemApp,
                "installTime" to app.installTime,
                "install_time" to app.installTime,
                "lastUpdateTime" to app.lastUpdateTime,
                "last_update_time" to app.lastUpdateTime,
                "signatureHash" to app.signatureHash,
                "signature_hash" to app.signatureHash,
                "permissions" to app.permissions,
                "accessibilityEnabled" to app.accessibilityEnabled,
                "riskScore" to app.riskScore,
                "classification" to app.classification
            )
        }

        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonce = java.util.UUID.randomUUID().toString()

        val payload = mapOf(
            "deviceId" to deviceId,
            "device_id" to deviceId,
            "tenantId" to tenantId,
            "tenant_id" to tenantId,
            "timestamp" to System.currentTimeMillis(),
            "count" to formattedApps.size,
            "apps" to formattedApps,
            "inventory" to formattedApps,
            "installed_apps" to formattedApps,
            "packages" to inventory.map { it.packageName }
        )

        val payloadJson = gson.toJson(payload)
        val signature = hmacSigner.signPayload(payloadJson, token, timestamp, nonce)

        try {
            Log.i(TAG, "Uploading app inventory snapshot (${formattedApps.size} apps) to Quasar backend...")
            val response = configService.reportInventory(
                authHeader = "Bearer $token",
                signature = signature,
                timestamp = timestamp,
                nonce = nonce,
                inventory = payload
            )
            if (response.isSuccessful) {
                Log.i(TAG, "Inventory successfully uploaded to Quasar (HTTP ${response.code()})")
                true
            } else {
                val errBody = try { response.errorBody()?.string()?.take(300) } catch (e: Exception) { null }
                Log.e(TAG, "Inventory sync failed: HTTP ${response.code()} - $errBody")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inventory sync network error", e)
            false
        }
    }
}
