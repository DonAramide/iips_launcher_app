package com.iips.launcher.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.iips.launcher.core.HardwareProvider
import com.iips.launcher.network.models.HeartbeatRequest
import com.iips.launcher.network.models.LocationInfo
import com.iips.launcher.security.TelemetryHmacSigner
import com.iips.launcher.storage.SecurePreferences
import com.iips.launcher.telemetry.TelemetryRepository
import com.iips.launcher.telemetry.TelemetrySequenceManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.tasks.await
import java.util.*

@HiltWorker
class TelemetryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: TelemetryRepository,
    private val sequenceManager: TelemetrySequenceManager,
    private val hmacSigner: TelemetryHmacSigner
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "TelemetryWorker"
        private const val WORK_NAME = "com.iips.launcher.work.TELEMETRY_SYNC"

        fun schedule(context: Context) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()

            val request = androidx.work.PeriodicWorkRequestBuilder<TelemetryWorker>(1, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

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
            
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val nonce = UUID.randomUUID().toString()
            val signature = hmacSigner.signPayload(heartbeat.toString(), token, timestamp, nonce)
            
            val response = repository.sendHeartbeat(token, signature, timestamp, nonce, heartbeat)
            
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
            deviceId = SecurePreferences.getDeviceId(context) ?: "unknown",
            tenantId = SecurePreferences.getTenantId(context) ?: "default",
            telemetrySeq = sequenceManager.getNextSequence(),
            batteryLevel = batteryInfo.level,
            isCharging = batteryInfo.charging,
            networkStatus = if (networkInfo.isConnected) networkInfo.type else "offline",
            uptime = android.os.SystemClock.elapsedRealtime() / 1000,
            location = if (locationInfo.lat != 0.0) locationInfo else null,
            deviceTime = System.currentTimeMillis()
        )
    }

    private suspend fun fetchCurrentLocation(context: Context): LocationInfo {
        return try {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            val location = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            
            if (location != null) {
                LocationInfo(lat = location.latitude, lng = location.longitude)
            } else {
                val lastKnown = fusedLocationClient.lastLocation.await()
                if (lastKnown != null) {
                    LocationInfo(lastKnown.latitude, lastKnown.longitude)
                } else {
                    LocationInfo(0.0, 0.0)
                }
            }
        } catch (e: SecurityException) {
            LocationInfo(0.0, 0.0)
        } catch (e: Exception) {
            LocationInfo(0.0, 0.0)
        }
    }
}
