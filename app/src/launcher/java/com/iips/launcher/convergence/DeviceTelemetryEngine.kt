package com.iips.launcher.convergence

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceTelemetryBatchPayload(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("tenant_id") val tenantId: String,
    @SerializedName("collected_at") val collectedAt: Long,
    @SerializedName("battery_level") val batteryLevel: Int,
    @SerializedName("is_charging") val isCharging: Boolean,
    @SerializedName("memory_available_mb") val memoryAvailableMb: Long,
    @SerializedName("memory_total_mb") val memoryTotalMb: Long,
    @SerializedName("storage_available_mb") val storageAvailableMb: Long,
    @SerializedName("storage_total_mb") val storageTotalMb: Long,
    @SerializedName("network_type") val networkType: String,
    @SerializedName("is_kiosk_locked") val isKioskLocked: Boolean,
    @SerializedName("launcher_uptime_sec") val launcherUptimeSec: Long,
    @SerializedName("app_crash_count") val appCrashCount: Int,
    @SerializedName("policy_version") val policyVersion: String,
    @SerializedName("integrity_score") val integrityScore: Double
)

/**
 * PHASE 2 — DEVICE TELEMETRY ENGINE
 * 
 * High-fidelity edge execution engine harvesting hardware profiles, memory partitions, disk footprints,
 * network states, and runtime anomalies. Enforces throttled telemetry batching, offline persistent
 * buffer queues, and bounded local storage retention metrics.
 */
@Singleton
class DeviceTelemetryEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: DotroidRuntimeConnectionManager,
    private val replayRuntime: ReplayRecoveryRuntime
) {
    companion object {
        private const val TAG = "DeviceTelemetryEngine"
        private const val TELEMETRY_HARVEST_INTERVAL_MS = 60_000L // 60 seconds throttled cadence
        private const val MAX_OFFLINE_BUFFER_LIMIT = 200 // Max stored items to prevent disk exhaustion
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bufferMutex = Mutex()
    private val isHarvesting = AtomicBoolean(false)
    private var harvestJob: Job? = null
    private val gson = Gson()
    private val engineStartTime = SystemClock.elapsedRealtime()

    /**
     * Commences active background edge hardware state logging sequences.
     */
    fun startHarvesting() {
        if (isHarvesting.getAndSet(true)) return
        Log.i(TAG, "Bootstrapping Dotroid Device Telemetry convergence stream.")

        harvestJob = scope.launch {
            while (isActive && isHarvesting.get()) {
                harvestAndRouteTelemetry()
                delay(TELEMETRY_HARVEST_INTERVAL_MS)
            }
        }
    }

    private suspend fun harvestAndRouteTelemetry() {
        try {
            val payload = collectTelemetrySnapshot()
            val contractCMap = mapOf(
                "type" to "EDGE_TELEMETRY_BATCH",
                "edgeNodeId" to payload.deviceId,
                "tenantId" to payload.tenantId,
                "transmittedAt" to (System.currentTimeMillis() / 1000L),
                "metrics" to mapOf(
                    "batteryLevelPct" to payload.batteryLevel.toDouble(),
                    "isCharging" to payload.isCharging,
                    "thermalState" to "NORMAL",
                    "jvmMemoryUsedMegabytes" to (payload.memoryTotalMb - payload.memoryAvailableMb).toDouble(),
                    "freeStorageMegabytes" to payload.storageAvailableMb.toDouble(),
                    "activeNetworkType" to payload.networkType,
                    "signalStrengthDbm" to -50
                ),
                "activeStateFlags" to listOfNotNull(
                    if (payload.isKioskLocked) "KIOSK_MODE_LOCKED" else null,
                    "INTEGRITY_SCORE_${payload.integrityScore}"
                ),
                // Preserve legacy payload properties to guarantee backwards-compatible convergence handovers
                "payload" to payload
            )
            val frameJson = gson.toJson(contractCMap)

            val sentDirectly = connectionManager.transmitFrame(frameJson)
            if (!sentDirectly) {
                Log.d(TAG, "Live network pipe saturated/offline. Persisting batch frame to offline local buffers.")
                replayRuntime.bufferFrame(frameJson)
            } else {
                replayRuntime.attemptJournalDrain()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Telemetry gathering failure trapped", e)
        }
    }

    private fun collectTelemetrySnapshot(): DeviceTelemetryBatchPayload {
        val deviceId = SecurePreferences.getDeviceId(context) ?: "UNKNOWN_DEVICE"
        
        // 1. Battery subsystem metrics
        val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else 50
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        // 2. Memory limits
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val availMemMb = memoryInfo.availMem / (1024 * 1024)
        val totalMemMb = memoryInfo.totalMem / (1024 * 1024)

        // 3. Disk space footprint
        val statFs = StatFs(Environment.getDataDirectory().path)
        val blockSize = statFs.blockSizeLong
        val availableBlocks = statFs.availableBlocksLong
        val totalBlocks = statFs.blockCountLong
        val availStorageMb = (availableBlocks * blockSize) / (1024 * 1024)
        val totalStorageMb = (totalBlocks * blockSize) / (1024 * 1024)

        // 4. Active Network pipeline signals
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val netType = when {
            capabilities == null -> "OFFLINE"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "UNKNOWN"
        }

        // 5. Kiosk containment metrics
        val isKioskLocked = com.iips.launcher.policy.DeviceAdminReceiver.isDeviceOwner(context) || 
                            activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

        // 6. Uptime computation
        val uptimeSec = (SystemClock.elapsedRealtime() - engineStartTime) / 1000L

        // 7. Policy hash versioning
        val policyVersion = SecurePreferences.getDevicePolicySnapshot(context)?.version ?: "UNSYNCHRONIZED"

        // 8. Trapped exception counter history
        val crashCount = SecurePreferences.getCommandAttemptCount(context, "APP_CRASH_HISTORY")
        val tenantId = SecurePreferences.getTenantId(context) ?: "default"

        return DeviceTelemetryBatchPayload(
            deviceId = deviceId,
            tenantId = tenantId,
            collectedAt = System.currentTimeMillis(),
            batteryLevel = batteryPct,
            isCharging = isCharging,
            memoryAvailableMb = availMemMb,
            memoryTotalMb = totalMemMb,
            storageAvailableMb = availStorageMb,
            storageTotalMb = totalStorageMb,
            networkType = netType,
            isKioskLocked = isKioskLocked,
            launcherUptimeSec = uptimeSec,
            appCrashCount = crashCount,
            policyVersion = policyVersion,
            integrityScore = connectionManager.healthScore
        )
    }

    /**
     * Buffers offline records securely utilizing bounded SharedPreferences lists to prevent heap overflow.
     */
    private suspend fun bufferOfflinePayload(frameJson: String) = bufferMutex.withLock {
        val currentBuffer = SecurePreferences.getOfflineTelemetryQueue(context).toMutableList()
        // Bounded retention validation check
        if (currentBuffer.size >= MAX_OFFLINE_BUFFER_LIMIT) {
            Log.w(TAG, "Offline queue boundary ($MAX_OFFLINE_BUFFER_LIMIT) breached. Evicting oldest telemetry slice.")
            currentBuffer.removeAt(0)
        }
        currentBuffer.add(frameJson)
        SecurePreferences.setOfflineTelemetryQueue(context, currentBuffer)
    }

    /**
     * Drains pending historical cached telemetry files deterministically on transport line recovery.
     */
    private suspend fun flushOfflineBuffers() = bufferMutex.withLock {
        val pendingItems = SecurePreferences.getOfflineTelemetryQueue(context)
        if (pendingItems.isEmpty()) return@withLock

        Log.i(TAG, "Connection intact. Draining ${pendingItems.size} buffered offline telemetry frames.")
        val remainingQueue = mutableListOf<String>()

        for (frame in pendingItems) {
            val sent = connectionManager.transmitFrame(frame)
            if (!sent) {
                // If line drops midway during flush, save un-transmitted items
                remainingQueue.add(frame)
            }
        }

        SecurePreferences.setOfflineTelemetryQueue(context, remainingQueue)
        if (remainingQueue.isEmpty()) {
            Log.d(TAG, "Offline telemetry replay successfully exhausted.")
        }
    }

    /**
     * Ceases collection tasks immediately.
     */
    fun stopHarvesting() {
        isHarvesting.set(false)
        harvestJob?.cancel()
    }

    suspend fun dispatchTelemetrySnapshot() {
        harvestAndRouteTelemetry()
    }
}
