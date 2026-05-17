package com.iips.launcher.convergence

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import com.iips.launcher.network.models.MdmCommand
import com.iips.launcher.policy.DeviceAdminReceiver
import com.iips.launcher.policy.KioskController
import com.iips.launcher.storage.SecurePreferences
import com.iips.launcher.ui.LauncherActivity
import com.iips.launcher.workers.PolicySyncWorker
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 4 — REMOTE COMMAND EXECUTION
 * 
 * Replay-safe remote operational execution orchestrator. Handles runtime-isolated critical system
 * commands (`REBOOT`, `RELAUNCH`, `OTA_TRIGGER`, `KIOSK_REFRESH`, `POLICY_SYNC`, `APP_REFRESH`, `REMOTE_DIAGNOSTICS`).
 * Provides comprehensive execution audit lineage histories and prevents duplicate processing loops.
 */
@Singleton
class RemoteCommandExecutionEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: DotroidRuntimeConnectionManager,
    private val otaCoordinator: Lazy<OtaRuntimeCoordinator>,
    private val telemetryEngine: Lazy<DeviceTelemetryEngine>
) {
    companion object {
        private const val TAG = "RemoteCmdExecEngine"
        const val CMD_REBOOT = "REBOOT"
        const val CMD_RELAUNCH = "RELAUNCH"
        const val CMD_OTA_TRIGGER = "OTA_TRIGGER"
        const val CMD_KIOSK_REFRESH = "KIOSK_REFRESH"
        const val CMD_POLICY_SYNC = "POLICY_SYNC"
        const val CMD_APP_REFRESH = "APP_REFRESH"
        const val CMD_REMOTE_DIAGNOSTICS = "REMOTE_DIAGNOSTICS"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val gson = Gson()

    /**
     * Executes validated command frames securely inside isolated transaction boundaries.
     */
    fun processCommandFrame(commandJson: String) {
        scope.launch {
            try {
                val command = gson.fromJson(commandJson, MdmCommand::class.java) ?: return@launch
                Log.i(TAG, "Ingesting Edge Command Context [ID: ${command.id}, Target Type: ${command.type}]")

                // Idempotency validation guard: suppress duplicate payload executions
                if (SecurePreferences.isCommandExecuted(context, command.id)) {
                    Log.w(TAG, "Duplicate command processing suppression triggered. Skipping execution for ID: ${command.id}")
                    acknowledgeExecution(command.id, "DUPLICATE_SUPPRESSED", "Command already mapped in transaction log.")
                    return@launch
                }

                // Acknowledge execution state commencement
                acknowledgeExecution(command.id, "EXECUTING", "Initiating runtime execution flow.")

                var successStatus = "SUCCESS"
                var finalMessage = "Command executed successfully."

                when (command.type.uppercase()) {
                    CMD_REBOOT -> {
                        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                        val admin = DeviceAdminReceiver.getComponentName(context)
                        if (DeviceAdminReceiver.isDeviceOwner(context)) {
                            Log.w(TAG, "Executing Device Owner operational reboot request.")
                            // Delay slightly to ensure ACK transmits successfully
                            withContext(Dispatchers.IO) { delay(1000) }
                            dpm.reboot(admin)
                        } else {
                            successStatus = "FAILED"
                            finalMessage = "Reboot blocked: Target application lacks Device Owner runtime constraints."
                        }
                    }
                    CMD_RELAUNCH -> {
                        Log.i(TAG, "Triggering persistent top-level launcher canvas refresh.")
                        val intent = Intent(context, LauncherActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                        context.startActivity(intent)
                    }
                    CMD_OTA_TRIGGER -> {
                        Log.i(TAG, "Delegating verified payload frame directly to OtaRuntimeCoordinator convergence pipes.")
                        otaCoordinator.get().processOtaTarget(command.payload)
                    }
                    CMD_KIOSK_REFRESH -> {
                        Log.i(TAG, "Synchronizing local visual hardware invariants.")
                        try {
                            KioskController.applyPolicy(context)
                        } catch (e: Exception) {
                            successStatus = "FAILED"
                            finalMessage = "Kiosk mode refresh exception: ${e.message}"
                        }
                    }
                    CMD_POLICY_SYNC -> {
                        Log.i(TAG, "Enforcing immediate Policy Synchronization schedule request.")
                        PolicySyncWorker.schedule(context)
                    }
                    CMD_APP_REFRESH -> {
                        Log.i(TAG, "Requesting immediate App Inventory reconciliation workers.")
                        com.iips.launcher.apps.inventory.AppInventoryWorker.schedule(context)
                    }
                    CMD_REMOTE_DIAGNOSTICS -> {
                        Log.i(TAG, "Discharging immediate Telemetry batch diagnostics capture.")
                        telemetryEngine.get().startHarvesting()
                    }
                    else -> {
                        successStatus = "FAILED"
                        finalMessage = "Unmapped target command profile type: ${command.type}"
                        Log.e(TAG, finalMessage)
                    }
                }

                // Finalize execution audits
                SecurePreferences.markCommandAsExecuted(context, command.id)
                acknowledgeExecution(command.id, successStatus, finalMessage)

            } catch (e: Exception) {
                Log.e(TAG, "Critical task execution failure encountered", e)
            }
        }
    }

    private suspend fun acknowledgeExecution(commandId: String, status: String, auditMessage: String) {
        val tenantId = SecurePreferences.getTenantId(context) ?: "default"
        val deviceId = SecurePreferences.getDeviceId(context) ?: "UNKNOWN_DEVICE"
        val ackFrame = gson.toJson(mapOf(
            "type" to "COMMAND_LINEAGE_AUDIT",
            "edgeNodeId" to deviceId,
            "tenantId" to tenantId,
            "transmittedAt" to (System.currentTimeMillis() / 1000L),
            "payload" to mapOf(
                "command_id" to commandId,
                "status" to status,
                "message" to auditMessage
            )
        ))

        val sent = connectionManager.transmitFrame(ackFrame)
        if (!sent) {
            Log.d(TAG, "Socket saturated. Adding line item execution audit to offline queue storage.")
            val queue = SecurePreferences.getOfflineTelemetryQueue(context).toMutableList()
            queue.add(ackFrame)
            SecurePreferences.setOfflineTelemetryQueue(context, queue)
        }
    }
}
