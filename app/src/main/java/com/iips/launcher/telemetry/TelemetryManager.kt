package com.iips.launcher.telemetry

import android.content.Context
import android.os.Build
import android.util.Log
import com.iips.launcher.core.HardwareProvider
import com.iips.launcher.network.models.HeartbeatRequest
import com.iips.launcher.security.FleetCredentialManager
import com.iips.launcher.security.SecurityUtils
import com.iips.launcher.security.TelemetryHmacSigner
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelemetryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetryRepository: TelemetryRepository,
    private val sequenceManager: TelemetrySequenceManager,
    private val credentialManager: FleetCredentialManager,
    private val hmacSigner: TelemetryHmacSigner
) {
    companion object {
        private const val TAG = "TelemetryManager"
    }

    suspend fun generateAndQueueHeartbeat() = withContext(Dispatchers.IO) {
        val deviceId = SecurePreferences.getDeviceId(context) ?: return@withContext
        val tenantId = SecurePreferences.getTenantId(context) ?: "default"
        val batteryInfo = HardwareProvider.getBatteryInfo(context)
        val networkInfo = HardwareProvider.getNetworkInfo(context)

        val heartbeat = HeartbeatRequest(
            deviceId = deviceId,
            tenantId = tenantId,
            telemetrySeq = sequenceManager.getNextSequence(),
            batteryLevel = batteryInfo.level,
            isCharging = batteryInfo.charging,
            networkStatus = if (networkInfo.isConnected) networkInfo.type else "offline",
            uptime = android.os.SystemClock.elapsedRealtime() / 1000,
            location = null,
            deviceTime = System.currentTimeMillis()
        )
        telemetryRepository.saveToOfflineQueue(heartbeat)
    }

    suspend fun flushOfflineQueue(): Boolean = withContext(Dispatchers.IO) {
        val token = SecurePreferences.getDeviceToken(context) ?: return@withContext false
        val pending = telemetryRepository.getPendingTelemetry()
        if (pending.isEmpty()) return@withContext true
        
        val gson = com.google.gson.Gson()
        val heartbeats = pending.map { gson.fromJson(it.payload, HeartbeatRequest::class.java) }
        
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonce = UUID.randomUUID().toString()
        val signature = hmacSigner.signPayload(heartbeats.toString(), token, timestamp, nonce)
        
        return@withContext try {
            val response = telemetryRepository.sendHeartbeatBatch(token, signature, timestamp, nonce, heartbeats)
            if (response.isSuccessful) {
                telemetryRepository.deleteBatch(pending.map { it.id })
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Batch flush failed", e)
            false
        }
    }
}
