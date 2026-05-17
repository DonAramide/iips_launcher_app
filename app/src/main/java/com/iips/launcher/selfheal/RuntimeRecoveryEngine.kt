package com.iips.launcher.selfheal

import android.util.Log
import com.google.gson.Gson
import com.iips.launcher.core.StructuredLogger
import com.iips.launcher.convergence.BroadcastRenderingEngine
import com.iips.launcher.kiosk.KioskManager
import com.iips.launcher.reconciliation.DriftEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes low-level recovery actions for specific drift events.
 */
@Singleton
class RuntimeRecoveryEngine @Inject constructor(
    private val kioskManager: KioskManager,
    private val structuredLogger: StructuredLogger,
    private val broadcastRenderingEngine: BroadcastRenderingEngine
) {
    companion object {
        private const val TAG = "RuntimeRecovery"
    }

    /**
     * Remediates a specific drift event.
     */
    fun remediate(event: DriftEvent) {
        Log.d(TAG, "Remediating event: \${event.javaClass.simpleName}")

        when (event) {
            is DriftEvent.LockTaskDeactivated -> {
                Log.w(TAG, "Restoring LockTask mode")
                // In a real app, you'd need the current activity context or a way to trigger startLockTask()
                // For now, we ensure the service is running which will attempt to return to launcher.
                kioskManager.startEnforcementService()
            }
            
            is DriftEvent.LauncherNotDefault -> {
                Log.w(TAG, "Launcher not default home. Kiosk state degraded.")
                val payloadJson = Gson().toJson(mapOf(
                    "broadcastId" to "recovery_kiosk_lock_${System.currentTimeMillis()}",
                    "tenantId" to "local_recovery",
                    "severity" to "CRITICAL",
                    "launcherMode" to "kiosk-lock",
                    "title" to "Security Alert",
                    "message" to "Device compliance degraded. Re-applying security policies...",
                    "requiresAcknowledgement" to false,
                    "timestamp" to System.currentTimeMillis()
                ))
                broadcastRenderingEngine.dispatchBroadcast(payloadJson)
            }

            is DriftEvent.AdminDeactivated -> {
                Log.e(TAG, "CRITICAL: Admin deactivated. Attempting re-activation notification.")
                structuredLogger.logIncident(TAG, "ADMIN_LOST", "Device Admin has been deactivated", fatal = true)
            }

            is DriftEvent.ProtectionDisabled -> {
                Log.w(TAG, "Protection \${event.name} disabled. Re-applying policy.")
                // This will be handled by the Drift Resolver in Phase 3
            }

            else -> {
                Log.d(TAG, "Event \${event.javaClass.simpleName} requires Phase 3 Drift Resolver")
            }
        }
    }
}
