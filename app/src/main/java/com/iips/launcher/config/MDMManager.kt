package com.iips.launcher.config

import android.content.Context
import android.os.Build
import android.util.Log
import com.iips.launcher.utils.SecurePreferences
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager as AndroidWorkManager
import com.iips.launcher.workers.TelemetryWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Manages MDM registration and telemetry heartbeat scheduling.
 */
object MDMManager {
    private const val TAG = "MDMManager"
    private val BASE_URL = com.iips.launcher.BuildConfig.BASE_URL

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.HEADERS
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(logging)
        .addInterceptor(MdmErrorInterceptor())
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .client(client)
        .build()

    private val service = retrofit.create(ConfigService::class.java)

    /**
     * Register the device with the Quasar server (§3.1).
     */
    suspend fun registerIfNeeded(context: Context, enrollmentToken: String? = null) = withContext(Dispatchers.IO) {
        if (SecurePreferences.isRegistered(context)) {
            Log.d(TAG, "Device already registered")
            return@withContext
        }

        // Use provided token or fallback to stored one
        val tokenToUse = enrollmentToken ?: SecurePreferences.getEnrollmentToken(context)
        if (tokenToUse == null) {
            Log.e(TAG, "Registration failed: No enrollment token available.")
            return@withContext
        }

        try {
            val manufacturer = android.os.Build.MANUFACTURER
            val model = android.os.Build.MODEL
            val serial = getSerialNumber()
            val fingerprintHash = com.iips.launcher.utils.SecurityUtils.calculateFingerprintHash(context)

            val request = RegistrationRequest(
                enrollment_token = tokenToUse,
                manufacturer = manufacturer,
                model = model,
                serial_number = serial,
                fingerprint_hash = fingerprintHash
            )

            Log.d(TAG, "Registering device via Quasar: $request")
            val response = service.registerDevice(request)

            if (response.isSuccessful) {
                response.body()?.let { reg ->
                    SecurePreferences.setDeviceId(context, reg.device.id)
                    SecurePreferences.setDeviceToken(context, reg.access_token)
                    SecurePreferences.setTokenExpiresAt(context, (System.currentTimeMillis() / 1000) + reg.expires_in)
                    SecurePreferences.setFingerprintHash(context, fingerprintHash)
                    SecurePreferences.setEnrollmentToken(context, tokenToUse)
                    
                    Log.d(TAG, "Registration successful. Device ID: ${reg.device.id}")
                    
                    // Start telemetry and policy sync
                    startHeartbeat(context)
                    startPolicySync(context)
                }
            } else {
                Log.e(TAG, "Registration failed: ${response.code()} ${response.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during registration", e)
        }
    }

    fun startHeartbeat(context: Context) {
        if (!SecurePreferences.isRegistered(context)) {
            Log.w(TAG, "Cannot start heartbeat: Device not registered")
            return
        }

        val workRequest = PeriodicWorkRequestBuilder<TelemetryWorker>(
            60, TimeUnit.MINUTES,
            15, TimeUnit.MINUTES // Flex interval
        ).build()

        AndroidWorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "mdm_heartbeat",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        Log.d(TAG, "Heartbeat scheduled (60m interval)")
    }

    fun startPolicySync(context: Context) {
        if (!SecurePreferences.isRegistered(context)) {
            Log.w(TAG, "Cannot start policy sync: Device not registered")
            return
        }

        // Run every 15 minutes as minimum periodic interval for WorkManager, 
        // though spec says 60-120 seconds. WorkManager minimum is 15 minutes.
        // For sub-15 min, we would need a Foreground Service or Handler.
        // Given we have MdmSocketService, we can also trigger it there, but here we schedule the fallback.
        val workRequest = PeriodicWorkRequestBuilder<com.iips.launcher.workers.PolicySyncWorker>(
            15, TimeUnit.MINUTES
        ).build()

        AndroidWorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "mdm_policy_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        Log.d(TAG, "Policy Sync scheduled (15m interval)")
    }

    /**
     * Force an immediate telemetry sync.
     */
    fun forceHeartbeat(context: Context) {
        val workRequest = androidx.work.OneTimeWorkRequestBuilder<TelemetryWorker>().build()
        AndroidWorkManager.getInstance(context).enqueue(workRequest)
        Log.d(TAG, "Manual heartbeat triggered")
    }

    /**
     * Force an immediate policy sync.
     */
    fun forcePolicySync(context: Context) {
        val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.iips.launcher.workers.PolicySyncWorker>().build()
        AndroidWorkManager.getInstance(context).enqueue(workRequest)
        Log.d(TAG, "Manual policy sync triggered")
    }

    /**
     * Reset MDM registration data.
     */
    fun resetRegistration(context: Context) {
        val prefs = context.getSharedPreferences("launcher_secure_prefs", Context.MODE_PRIVATE)
        // Note: Using raw prefs here because SecurePreferences doesn't have a clearSpecific helper
        // but we can just set them to null.
        SecurePreferences.setDeviceId(context, "") // Using empty to signal reset if null isn't supported well
        SecurePreferences.setDeviceToken(context, "")
        
        val encryptedPrefs = context.getSharedPreferences("launcher_secure_prefs", Context.MODE_PRIVATE)
        encryptedPrefs.edit().remove("device_id").remove("device_token").apply()
        
        AndroidWorkManager.getInstance(context).cancelUniqueWork("mdm_heartbeat")
        Log.d(TAG, "MDM registration reset and heartbeat cancelled")
    }

    /**
     * Get the device serial number. 
     * Note: Build.SERIAL is deprecated and restricted on newer Android versions.
     * For MDM, we usually use Build.getSerial() if we are Device Owner.
     */
    private fun getSerialNumber(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Requires READ_PHONE_STATE permission or being Device Owner
                Build.getSerial()
            } else {
                Build.SERIAL
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get serial number: ${e.message}")
            "UNKNOWN_${Build.ID}"
        }
    }

}
