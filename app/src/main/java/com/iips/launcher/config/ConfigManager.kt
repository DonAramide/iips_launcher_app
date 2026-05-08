package com.iips.launcher.config

import android.content.Context
import android.util.Log
import com.iips.launcher.device.DeviceAdminReceiver
import com.iips.launcher.utils.DeviceController
import com.iips.launcher.utils.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Manages fetching and applying remote configuration.
 */
object ConfigManager {
    private const val TAG = "ConfigManager"
    private const val DEFAULT_CONFIG_URL = "https://your-server.com/config.json" // Placeholder

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(logging)
        .addInterceptor(MdmErrorInterceptor())
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(com.iips.launcher.BuildConfig.BASE_URL) // Used for standard endpoint calls
        .addConverterFactory(GsonConverterFactory.create())
        .client(client)
        .build()

    private val service = retrofit.create(ConfigService::class.java)



    /**
     * Check device activation status from the server (§3.1 check).
     */
    suspend fun checkActivationStatus(context: Context, deviceId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Re-register or refresh to check status
            val token = SecurePreferences.getDeviceToken(context) ?: return@withContext false
            val response = service.fetchPolicy("Bearer $token")
            if (response.isSuccessful) {
                return@withContext true // If we can fetch policy, we are active
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking activation status: ${e.message}")
            false
        }
    }
}
