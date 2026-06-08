package com.iips.launcher.storage

import android.content.Context
import android.util.Log
import com.iips.launcher.network.ConfigService
import com.iips.launcher.storage.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigManager @Inject constructor(
    private val service: ConfigService
) {
    private val TAG = "ConfigManager"

    suspend fun checkActivationStatus(context: Context, deviceId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = SecurePreferences.getDeviceToken(context) ?: return@withContext false
            val response = service.fetchPolicy("Bearer $token")
            return@withContext response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Error checking activation status: \${e.message}")
            false
        }
    }
}
