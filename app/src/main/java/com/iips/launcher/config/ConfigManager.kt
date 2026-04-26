package com.iips.launcher.config

import android.content.Context
import android.util.Log
import com.iips.launcher.device.DeviceAdminReceiver
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
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://placeholder.com/") // Base URL is required but overridden by @Url
        .addConverterFactory(GsonConverterFactory.create())
        .client(client)
        .build()

    private val service = retrofit.create(ConfigService::class.java)

    /**
     * Synchronize configuration with the server.
     */
    suspend fun sync(context: Context, customUrl: String? = null) = withContext(Dispatchers.IO) {
        val url = customUrl ?: DEFAULT_CONFIG_URL
        
        try {
            Log.d(TAG, "Fetching config from: $url")
            val response = service.fetchConfig(url)
            
            if (response.isSuccessful) {
                response.body()?.let { config ->
                    applyConfig(context, config)
                    Log.d(TAG, "Config sync successful")
                }
            } else {
                Log.e(TAG, "Config fetch failed: ${response.code()} ${response.message()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing config: ${e.message}", e)
        }
    }

    /**
     * Apply the fetched configuration to the app settings and device policy.
     */
    private fun applyConfig(context: Context, config: ConfigResponse) {
        // 1. Update Organization Name
        config.organizationName?.let { name ->
            SecurePreferences.setOrganizationName(context, name)
            // Apply to Device Policy if possible
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                val admin = DeviceAdminReceiver.getComponentName(context)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    dpm.setOrganizationName(admin, name)
                }
                Unit
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply org name to DPM: ${e.message}")
            }
        }

        // 2. Update Admin Password
        config.adminPassword?.let { password ->
            SecurePreferences.setAdminPassword(context, password)
        }

        // 3. Update Lockdown Mode
        config.lockdownEnabled?.let { enabled ->
            SecurePreferences.setLockdownEnabled(context, enabled)
        }

        // 4. Update Allowed Apps
        config.allowedPackages?.let { packages ->
            SecurePreferences.setAllowedApps(context, packages.toSet())
        }
        
        Log.d(TAG, "Applied remote configuration successfully")
    }
}
