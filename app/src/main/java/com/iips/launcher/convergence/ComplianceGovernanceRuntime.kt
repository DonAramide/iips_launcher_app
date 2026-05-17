package com.iips.launcher.convergence

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.google.gson.Gson
import com.iips.launcher.policy.DeviceAdminReceiver
import com.iips.launcher.policy.KioskController
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 6 — COMPLIANCE & KIOSK GOVERNANCE
 * 
 * Persistent background governance execution monitor validating uncompromised execution boundaries.
 * Continuously evaluates Device Owner runtime flags, active Lock Task status, baseline default launcher
 * assignments, and active admin status. Automatically invokes real-time telemetry alerts, displays native
 * locking overlays, and attempts recursive self-healing recovery actions.
 */
@Singleton
class ComplianceGovernanceRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: DotroidRuntimeConnectionManager,
    private val broadcastEngine: BroadcastRenderingEngine
) {
    companion object {
        private const val TAG = "ComplianceGovRuntime"
        private const val GOVERNANCE_POLL_INTERVAL_MS = 15_000L // 15-second baseline continuous checks
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isMonitoring = AtomicBoolean(false)
    private var monitorJob: Job? = null
    private val gson = Gson()

    /**
     * Bootstraps recursive invariant polling loops.
     */
    fun startMonitoring() {
        if (isMonitoring.getAndSet(true)) return
        Log.i(TAG, "Initializing active continuous Kiosk Governance compliance stream.")

        monitorJob = scope.launch {
            while (isActive && isMonitoring.get()) {
                evaluateKioskInvariants()
                delay(GOVERNANCE_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun evaluateKioskInvariants() {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val packageManager = context.packageManager
        val adminComponent = DeviceAdminReceiver.getComponentName(context)

        val isDo = DeviceAdminReceiver.isDeviceOwner(context)
        val isAdminActive = dpm.isAdminActive(adminComponent)
        val isLockTaskActive = activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

        val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        val resolveInfo = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val isDefaultLauncher = resolveInfo?.activityInfo?.packageName == context.packageName

        val failedInvariants = mutableListOf<String>()
        if (!isDo) failedInvariants.add("DEVICE_OWNER_REVOKED")
        if (!isAdminActive) failedInvariants.add("ADMIN_COMPONENT_INACTIVE")
        if (!isLockTaskActive) failedInvariants.add("LOCK_TASK_ESCAPED")
        if (!isDefaultLauncher) failedInvariants.add("DEFAULT_LAUNCHER_UNMAPPED")

        if (failedInvariants.isNotEmpty()) {
            Log.e(TAG, "CRITICAL: Continuous compliance invariants breached! Deviations: $failedInvariants")
            
            // Dispatch live Real-time compliance alert packets
            emitComplianceAlert(failedInvariants)

            // Trigger fallback native quarantine rendering overlays safely
            renderQuarantineOverlay(failedInvariants)

            // Auto-retry Self-Healing verification tasks
            executeSelfHealingRemediations(isLockTaskActive)
        } else {
            Log.v(TAG, "Kiosk Containment metrics verified pristine.")
        }
    }

    private suspend fun emitComplianceAlert(violations: List<String>) {
        val deviceId = SecurePreferences.getDeviceId(context) ?: "UNKNOWN_DEVICE"
        val tenantId = SecurePreferences.getTenantId(context) ?: "default"

        val alertPayload = gson.toJson(mapOf(
            "type" to "COMPLIANCE_INVARIANT_BREACH",
            "edgeNodeId" to deviceId,
            "tenantId" to tenantId,
            "transmittedAt" to (System.currentTimeMillis() / 1000L),
            "payload" to mapOf(
                "violations" to violations
            )
        ))

        val sent = connectionManager.transmitFrame(alertPayload)
        if (!sent) {
            val queue = SecurePreferences.getOfflineTelemetryQueue(context).toMutableList()
            queue.add(alertPayload)
            SecurePreferences.setOfflineTelemetryQueue(context, queue)
        }
    }

    private fun renderQuarantineOverlay(violations: List<String>) {
        val tenantId = SecurePreferences.getTenantId(context) ?: "default"
        val payload = BroadcastPayload(
            broadcastId = "compliance-lock-${System.currentTimeMillis()}",
            tenantId = tenantId,
            severity = "CRITICAL",
            launcherMode = BroadcastRenderingEngine.MODE_KIOSK_LOCK,
            title = "CRITICAL: COMPLIANCE LOCKDOWN",
            message = "Device invariants degraded (${violations.joinToString()}). Re-authorizing execution constraints.",
            requiresAcknowledgement = true
        )
        broadcastEngine.dispatchBroadcast(gson.toJson(payload))
    }

    private fun executeSelfHealingRemediations(isLockTaskActive: Boolean) {
        scope.launch(Dispatchers.Main) {
            try {
                if (!isLockTaskActive) {
                    Log.w(TAG, "Attempting recursive automatic self-healing loops.")
                    KioskController.applyPolicy(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Self-healing Lock Task re-entry failed", e)
            }
        }
    }

    fun stopMonitoring() {
        isMonitoring.set(false)
        monitorJob?.cancel()
    }
}
