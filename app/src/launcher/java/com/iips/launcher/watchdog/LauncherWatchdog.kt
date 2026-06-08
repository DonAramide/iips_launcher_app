package com.iips.launcher.watchdog

import android.content.Context
import android.util.Log
import com.iips.launcher.deviceowner.OwnershipWatchdog
import com.iips.launcher.kiosk.KioskManager
import com.iips.launcher.policy.PolicyManager
import com.iips.launcher.workers.TelemetryWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level watchdog that ensures critical launcher subsystems are healthy and running.
 */
@Singleton
class LauncherWatchdog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ownershipWatchdog: OwnershipWatchdog,
    private val policyManager: PolicyManager,
    private val kioskManager: KioskManager
) {
    companion object {
        private const val TAG = "LauncherWatchdog"
        private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var watchdogJob: Job? = null

    /**
     * Starts the global watchdog.
     */
    fun start() {
        if (watchdogJob?.isActive == true) return
        
        watchdogJob = scope.launch {
            while (isActive) {
                Log.d(TAG, "Watchdog: Running health check for all subsystems")
                performHealthCheck()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private fun performHealthCheck() {
        try {
            // 1. Ensure OwnershipWatchdog is alive
            ownershipWatchdog.start()
            
            // 2. Ensure Kiosk enforcement is running if enabled
            kioskManager.startEnforcementService()
            
            // 3. Ensure Telemetry is scheduled
            TelemetryWorker.schedule(context)
            
            // 4. Verify Policy enforcement
            policyManager.enforceCurrent()
            
        } catch (e: Exception) {
            Log.e(TAG, "Global health check encountered an error", e)
        }
    }

    /**
     * Stops the global watchdog.
     */
    fun stop() {
        watchdogJob?.cancel()
    }
}
