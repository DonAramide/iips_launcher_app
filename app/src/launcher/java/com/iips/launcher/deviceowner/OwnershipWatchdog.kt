package com.iips.launcher.deviceowner

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watchdog that monitors ownership state and triggers recovery if needed.
 */
@Singleton
class OwnershipWatchdog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val verifier: DeviceOwnerVerifier
) {
    companion object {
        private const val TAG = "OwnershipWatchdog"
        private const val CHECK_INTERVAL_MS = 60 * 60 * 1000L // 1 hour
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    /**
     * Starts the watchdog monitoring loop.
     */
    fun start() {
        if (job?.isActive == true) return
        
        job = scope.launch {
            while (isActive) {
                Log.d(TAG, "Watchdog: Running periodic ownership verification")
                try {
                    verifier.performVerification()
                } catch (e: Exception) {
                    Log.e(TAG, "Watchdog check failed", e)
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Stops the watchdog.
     */
    fun stop() {
        job?.cancel()
    }
}
