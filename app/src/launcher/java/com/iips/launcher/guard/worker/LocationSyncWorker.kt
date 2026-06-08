package com.iips.launcher.guard.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.iips.launcher.core.HardwareProvider
import com.iips.launcher.guard.data.GuardEventDao
import com.iips.launcher.guard.data.LocationReportDao
import com.iips.launcher.network.ConfigService
import com.iips.launcher.network.models.HeartbeatRequest
import com.iips.launcher.network.models.LocationInfo
import com.iips.launcher.network.models.MdmEventRequest
import com.iips.launcher.security.TelemetryHmacSigner
import com.iips.launcher.storage.SecurePreferences
import com.iips.launcher.telemetry.TelemetryRepository
import com.iips.launcher.telemetry.TelemetrySequenceManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID

@HiltWorker
class LocationSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val guardEventDao: GuardEventDao,
    private val locationReportDao: LocationReportDao,
    private val configService: ConfigService,
    private val telemetryRepository: TelemetryRepository,
    private val sequenceManager: TelemetrySequenceManager,
    private val hmacSigner: TelemetryHmacSigner
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "LocationSyncWorker"
        private const val WORK_NAME = "com.iips.launcher.guard.work.LOCATION_SYNC"

        fun schedule(context: Context) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()

            val request = androidx.work.PeriodicWorkRequestBuilder<LocationSyncWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30,
                    java.util.concurrent.TimeUnit.SECONDS
                )
                .build()

            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun startImmediateSync(context: Context) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()

            val request = androidx.work.OneTimeWorkRequestBuilder<LocationSyncWorker>()
                .setConstraints(constraints)
                .build()

            androidx.work.WorkManager.getInstance(context).enqueue(request)
        }
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        Log.d(TAG, "LocationSyncWorker sync loop executing...")

        if (!SecurePreferences.isRegistered(context)) {
            Log.w(TAG, "Device not registered, skipping Guard sync")
            return Result.success()
        }

        val token = SecurePreferences.getDeviceToken(context) ?: return Result.success()

        try {
            // 1. Sync pending events
            val unsyncedEvents = guardEventDao.getUnsyncedEvents()
            if (unsyncedEvents.isNotEmpty()) {
                Log.d(TAG, "Syncing ${unsyncedEvents.size} unsynced security events")
                for (event in unsyncedEvents) {
                    val payload = mutableMapOf<String, Any>(
                        "event_id" to event.id,
                        "timestamp" to event.timestamp
                    )
                    event.payloadJson?.let {
                        payload["details"] = it
                    }
                    
                    val request = MdmEventRequest(type = event.eventType, payload = payload)
                    val response = configService.sendEvent("Bearer $token", request)
                    if (response.isSuccessful) {
                        guardEventDao.markEventsSynced(listOf(event.id))
                    } else {
                        Log.e(TAG, "Failed to upload event ${event.id}: code=${response.code()}")
                    }
                }
                guardEventDao.pruneSyncedEvents()
            }

            // 2. Sync pending locations
            val unsyncedLocations = locationReportDao.getUnsyncedLocations()
            if (unsyncedLocations.isNotEmpty()) {
                Log.d(TAG, "Syncing ${unsyncedLocations.size} unsynced location reports")
                
                val batteryInfo = HardwareProvider.getBatteryInfo(context)
                val networkInfo = HardwareProvider.getNetworkInfo(context)
                val simInfo = HardwareProvider.getSimInfo(context)
                val simDetailsList = HardwareProvider.getSimDetails(context)
                val deviceId = SecurePreferences.getDeviceId(context) ?: "unknown"
                val tenantId = SecurePreferences.getTenantId(context) ?: "default"

                // We chunk location reports to avoid huge payloads
                val chunks = unsyncedLocations.chunked(50)
                for (chunk in chunks) {
                    val heartbeatRequests = chunk.map { report ->
                        HeartbeatRequest(
                            deviceId = deviceId,
                            tenantId = tenantId,
                            telemetrySeq = sequenceManager.getNextSequence(),
                            batteryLevel = batteryInfo.level,
                            isCharging = batteryInfo.charging,
                            networkStatus = if (networkInfo.isConnected) networkInfo.type else "offline",
                            uptime = android.os.SystemClock.elapsedRealtime() / 1000,
                            location = LocationInfo(report.latitude, report.longitude),
                            deviceTime = report.timestamp * 1000,
                            isSimPresent = simInfo.isPresent,
                            simOperator = simInfo.simOperator,
                            simNetworkType = simInfo.simNetworkType,
                            simDetails = simDetailsList,
                            serialNumber = deviceId
                        )
                    }

                    val gson = Gson()
                    val payloadStr = gson.toJson(heartbeatRequests)
                    val timestamp = (System.currentTimeMillis() / 1000).toString()
                    val nonce = UUID.randomUUID().toString()
                    val signature = hmacSigner.signPayload(payloadStr, token, timestamp, nonce)

                    val response = telemetryRepository.sendHeartbeatBatch(
                        token = token,
                        signature = signature,
                        timestamp = timestamp,
                        nonce = nonce,
                        requests = heartbeatRequests
                    )

                    if (response.isSuccessful) {
                        val ids = chunk.map { it.id }
                        locationReportDao.markLocationsSynced(ids)
                    } else {
                        Log.e(TAG, "Failed to upload location chunk: code=${response.code()}")
                        // Return retry if a batch sync fails
                        return Result.retry()
                    }
                }
                locationReportDao.pruneSyncedLocations()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in LocationSyncWorker", e)
            return Result.retry()
        }

        return Result.success()
    }
}
