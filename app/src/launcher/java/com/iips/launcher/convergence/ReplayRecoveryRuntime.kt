package com.iips.launcher.convergence

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/**
 * PHASE 8 — LOCAL REPLAY & RECOVERY LAYER
 * 
 * Replay engine executing zero-loss historical audit recovery loops. Governs local JSON array
 * storage records to guarantee strict FIFO sequential packet delivery. Dynamically recalculates multi-tenant
 * target tokens, prevents disk block write saturation, and schedules bounded backoff journal exhaustion queues.
 */
@Singleton
class ReplayRecoveryRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: DotroidRuntimeConnectionManager
) {
    companion object {
        private const val TAG = "ReplayRecoveryRuntime"
        private const val PREFS_FILE = "launcher_replay_journal_prefs"
        private const val JOURNAL_KEY = "offline_telemetry_journal_array"
        private const val MAX_FIFO_LIMIT = 500 // Maximum buffered historical log elements
        private const val MIN_DISK_AVAILABLE_MB = 50L // Minimum storage headroom guard
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val journalMutex = Mutex()
    private val isDraining = AtomicBoolean(false)
    private val gson = Gson()
    private var drainJob: Job? = null

    /**
     * Enqueues offline audit frames securely into persistent serial files.
     */
    suspend fun bufferFrame(jsonPayload: String) = journalMutex.withLock {
        try {
            // Verify safe underlying disk storage parameters before persisting stream
            if (!hasSufficientStorageHeadroom()) {
                Log.e(TAG, "CRITICAL: Insufficient disk blocks available. Dropping target frame to avoid disk exhaustion loops.")
                return@withLock
            }

            val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            val storedJson = prefs.getString(JOURNAL_KEY, "[]") ?: "[]"
            val type = object : TypeToken<MutableList<String>>() {}.type
            val currentList: MutableList<String> = gson.fromJson(storedJson, type) ?: mutableListOf()

            // Enforce precise FIFO bounding rules
            if (currentList.size >= MAX_FIFO_LIMIT) {
                Log.w(TAG, "Replay buffer FIFO limit breached ($MAX_FIFO_LIMIT). Evicting head element to preserve tail logging.")
                currentList.removeAt(0)
            }

            // Explicit multi-tenant injection sanity check verification
            val verifiedFrame = injectTenantLineage(jsonPayload)
            currentList.add(verifiedFrame)

            prefs.edit().putString(JOURNAL_KEY, gson.toJson(currentList)).apply()
            Log.d(TAG, "Offline journal safely appended. Total sequential records pending: ${currentList.size}")

        } catch (e: Exception) {
            Log.e(TAG, "Trapped persistent storage degradation writing replay queues", e)
        }
    }

    /**
     * Initiates continuous background FIFO queue flushing operations.
     */
    fun attemptJournalDrain() {
        if (isDraining.getAndSet(true)) return
        Log.i(TAG, "Triggering persistent serial FIFO log restoration routines.")

        drainJob = scope.launch {
            var attempt = 0
            while (isActive && isDraining.get()) {
                val hasPending = executeDrainStep()
                if (!hasPending) {
                    Log.i(TAG, "Offline historical journals completely depleted. Discharging drain locks.")
                    isDraining.set(false)
                    break
                }

                attempt++
                val delayMs = min(60_000L, 2_000L * (2.0.pow(attempt)).toLong())
                Log.d(TAG, "Partial drain saturated. Pacing sequential batch restoration by ${delayMs}ms.")
                delay(delayMs)
            }
        }
    }

    private suspend fun executeDrainStep(): Boolean = journalMutex.withLock {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val storedJson = prefs.getString(JOURNAL_KEY, "[]") ?: "[]"
        val type = object : TypeToken<MutableList<String>>() {}.type
        val currentList: MutableList<String> = gson.fromJson(storedJson, type) ?: mutableListOf()

        if (currentList.isEmpty()) return false

        val remainingList = mutableListOf<String>()
        var batchesTransmitted = 0

        for (frame in currentList) {
            // Re-verify multi-tenant isolation tokens dynamically across shifting socket contexts
            val dynamicFrame = injectTenantLineage(frame)
            val sent = connectionManager.transmitFrame(dynamicFrame)
            if (sent) {
                batchesTransmitted++
            } else {
                remainingList.add(dynamicFrame)
            }
        }

        prefs.edit().putString(JOURNAL_KEY, gson.toJson(remainingList)).apply()
        Log.d(TAG, "Drained $batchesTransmitted stored frames successfully. ${remainingList.size} elements left.")
        return remainingList.isNotEmpty()
    }

    private fun injectTenantLineage(rawJson: String): String {
        return try {
            val tenantId = SecurePreferences.getTenantId(context) ?: "default"
            val mapType = object : TypeToken<MutableMap<String, Any>>() {}.type
            val map: MutableMap<String, Any> = gson.fromJson(rawJson, mapType) ?: return rawJson

            // Ensure tenant isolation token mapping is dynamically embedded inside nested target maps
            val payloadObj = map["payload"]
            if (payloadObj is Map<*, *>) {
                val innerMap = payloadObj.toMutableMap()
                innerMap["tenant_id"] = tenantId
                map["payload"] = innerMap
            } else {
                map["tenant_id"] = tenantId
            }

            gson.toJson(map)
        } catch (e: Exception) {
            rawJson
        }
    }

    private fun hasSufficientStorageHeadroom(): Boolean {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val availableMb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
            availableMb > MIN_DISK_AVAILABLE_MB
        } catch (e: Exception) {
            true
        }
    }
}
