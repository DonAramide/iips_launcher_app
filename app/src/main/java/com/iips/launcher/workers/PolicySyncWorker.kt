package com.iips.launcher.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.iips.launcher.config.ConfigService
import com.iips.launcher.data.AppDatabase
import com.iips.launcher.mdm.PolicyEnforcer
import com.iips.launcher.utils.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.iips.launcher.config.MdmErrorInterceptor
import com.iips.launcher.config.DeviceNotActivatedException
import com.iips.launcher.config.RateLimitExceededException
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Worker responsible for fetching the latest App Policy from the backend
 * and triggering the PolicyEnforcer.
 */
class PolicySyncWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "PolicySyncWorker"
        private val BASE_URL = com.iips.launcher.BuildConfig.BASE_URL
    }

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
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

    private val configService = retrofit.create(ConfigService::class.java)

    private val gson = com.google.gson.Gson()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        
        if (!SecurePreferences.isRegistered(context)) {
            Log.w(TAG, "Device not registered, skipping policy sync")
            return@withContext Result.retry()
        }

        val deviceId = SecurePreferences.getDeviceId(context) ?: return@withContext Result.failure()
        val token = SecurePreferences.getDeviceToken(context) ?: return@withContext Result.failure()

        try {
            Log.d(TAG, "1. Fetching policy from backend...")
            val response = configService.fetchPolicy("Bearer $token")

            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch policy: ${response.code()}")
                return@withContext if (response.code() in 500..599) Result.retry() else Result.failure()
            }

            val policy = response.body() ?: return@withContext Result.failure()

            Log.d(TAG, "2. Optional security header check...")
            val signature = response.headers()["X-Signature"]
            if (signature != null) {
                val rawJson = gson.toJson(policy)
                val timestamp = response.headers()["X-Timestamp"]
                val nonce = response.headers()["X-Nonce"]
                val isValid = com.iips.launcher.utils.SecurityUtils.verifyHmac(
                    context, rawJson, signature, token, timestamp, nonce
                )
                if (!isValid) {
                    Log.e(TAG, "SECURITY ALERT: Policy signature verification failed!")
                    return@withContext Result.failure()
                }
            }

            Log.d(TAG, "3. Persisting snapshot (Atomic Commit)...")
            // Max policy age fallback to 24h if not provided (not in stub)
            val maxAge = 24 * 60 * 60 * 1000L 

            val newSnapshot = com.iips.launcher.data.DevicePolicySnapshot(
                version = response.headers()["X-Policy-Version"] ?: "v1",
                maxPolicyAge = maxAge,
                allowedApps = policy.allowed_apps,
                blockedApps = policy.blocked_apps,
                kioskMode = policy.kiosk_mode,
                settingsLock = policy.settings_lock,
                geofenceRules = policy.geofence_rules,
                installQueue = policy.install_queue,
                lastUpdatedAt = System.currentTimeMillis()
            )
            SecurePreferences.setDevicePolicySnapshot(context, newSnapshot)

            Log.d(TAG, "4. Applying enforcement...")
            PolicyEnforcer.enforcePolicy(context, newSnapshot)
            
            withContext(Dispatchers.Main) {
                com.iips.launcher.mdm.KioskController.applyPolicy(context)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in PolicySyncWorker pipeline", e)
            Result.retry()
        }
    }
}
