package com.iips.launcher.workers

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.iips.launcher.config.*
import com.iips.launcher.device.DeviceAdminReceiver
import com.iips.launcher.utils.HardwareProvider
import com.iips.launcher.utils.SecurePreferences
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Worker responsible for sending periodic heartbeat telemetry to the MDM server.
 */
class TelemetryWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "TelemetryWorker"
        private val BASE_URL = com.iips.launcher.BuildConfig.BASE_URL
    }

    // Manual instantiation of Retrofit for the worker context
    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(logging)
        .addInterceptor(com.iips.launcher.config.MdmErrorInterceptor())
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .client(client)
        .build()

    private val service = retrofit.create(ConfigService::class.java)

    override suspend fun doWork(): Result {
        val context = applicationContext
        
        if (!SecurePreferences.isRegistered(context)) {
            Log.w(TAG, "Device not registered, skipping heartbeat")
            return Result.retry()
        }

        val deviceId = SecurePreferences.getDeviceId(context) ?: return Result.failure()
        val token = SecurePreferences.getDeviceToken(context) ?: return Result.failure()

        try {
            val heartbeat = collectTelemetry(context)
            Log.d(TAG, "Sending heartbeat: $heartbeat")
            
            val response = service.sendHeartbeat("Bearer $token", heartbeat)
            
            return if (response.isSuccessful) {
                Log.d(TAG, "Heartbeat sent successfully")
                Result.success()
            } else {
                Log.e(TAG, "Heartbeat failed: ${response.code()}")
                if (response.code() in 500..599) Result.retry() else Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in TelemetryWorker", e)
            return Result.retry()
        }
    }

    private suspend fun collectTelemetry(context: Context): HeartbeatRequest {
        val locationInfo = fetchCurrentLocation(context)
        val batteryInfo = HardwareProvider.getBatteryInfo(context)
        val networkInfo = HardwareProvider.getNetworkInfo(context)

        return HeartbeatRequest(
            battery_level = batteryInfo.level,
            is_charging = batteryInfo.charging,
            network_status = if (networkInfo.isConnected) networkInfo.type else "offline",
            uptime = android.os.SystemClock.elapsedRealtime() / 1000,
            location = if (locationInfo.lat != 0.0) locationInfo else null
        )
    }

    /**
     * Fetch current location using FusedLocationProvider.
     */
    private suspend fun fetchCurrentLocation(context: Context): LocationInfo {
        return try {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            // Note: This requires location permissions to be granted.
            val location = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            
            if (location != null) {
                LocationInfo(
                    lat = location.latitude,
                    lng = location.longitude
                )
            } else {
                // Fallback to last known
                val lastKnown = fusedLocationClient.lastLocation.await()
                if (lastKnown != null) {
                    LocationInfo(lastKnown.latitude, lastKnown.longitude)
                } else {
                    LocationInfo(0.0, 0.0)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission missing: ${e.message}")
            LocationInfo(0.0, 0.0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch location", e)
            LocationInfo(0.0, 0.0)
        }
    }
}
