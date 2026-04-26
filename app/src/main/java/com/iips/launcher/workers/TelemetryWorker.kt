package com.iips.launcher.workers

import android.content.Context
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
        private const val BASE_URL = "https://api-quaser.iips.app/"
    }

    // Manual instantiation of Retrofit for the worker context
    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(logging)
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
            val heartbeat = collectTelemetry(context, deviceId)
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

    private suspend fun collectTelemetry(context: Context, deviceId: String): HeartbeatRequest {
        val locationInfo = fetchCurrentLocation(context)
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        return HeartbeatRequest(
            deviceId = deviceId,
            timestamp = isoFormat.format(Date()),
            location = locationInfo,
            battery = HardwareProvider.getBatteryInfo(context),
            uptimeSeconds = android.os.SystemClock.elapsedRealtime() / 1000,
            network = HardwareProvider.getNetworkInfo(context),
            sim = HardwareProvider.getSimInfo(context),
            deviceState = HardwareProvider.getDeviceStateInfo(context),
            printer = HardwareProvider.getPrinterInfo(context)
        )
    }

    /**
     * Fetch current location using FusedLocationProvider.
     */
    private suspend fun fetchCurrentLocation(context: Context): LocationInfo {
        return try {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            // Note: This requires location permissions to be granted.
            // Using getCurrentLocation for a fresh fix.
            val location = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            
            if (location != null) {
                LocationInfo(
                    lat = location.latitude,
                    lng = location.longitude,
                    accuracy = location.accuracy
                )
            } else {
                // Fallback to last known or default
                val lastKnown = fusedLocationClient.lastLocation.await()
                if (lastKnown != null) {
                    LocationInfo(lastKnown.latitude, lastKnown.longitude, lastKnown.accuracy)
                } else {
                    LocationInfo(0.0, 0.0, 0f) // Default if no location available
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission missing: ${e.message}")
            LocationInfo(0.0, 0.0, 0f)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch location", e)
            LocationInfo(0.0, 0.0, 0f)
        }
    }
}
