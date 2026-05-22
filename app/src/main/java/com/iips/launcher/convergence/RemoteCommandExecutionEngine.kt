package com.iips.launcher.convergence

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.iips.launcher.network.models.MdmCommand
import com.iips.launcher.ota.ApkInstallManager
import com.iips.launcher.policy.DeviceAdminReceiver
import com.iips.launcher.policy.KioskController
import com.iips.launcher.storage.SecurePreferences
import com.iips.launcher.ui.LauncherActivity
import com.iips.launcher.workers.PolicySyncWorker
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 4 — REMOTE COMMAND EXECUTION
 * 
 * Replay-safe remote operational execution orchestrator. Handles runtime-isolated critical system
 * commands (`REBOOT`, `RELAUNCH`, `OTA_TRIGGER`, `KIOSK_REFRESH`, `POLICY_SYNC`, `APP_REFRESH`, `REMOTE_DIAGNOSTICS`).
 * Provides comprehensive execution audit lineage histories and prevents duplicate processing loops.
 * Also handles real-time WebSocket frames for push-notifications, location updates, installs/uninstalls,
 * parameter configurations, and payment notifications.
 */
@Singleton
class RemoteCommandExecutionEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: DotroidRuntimeConnectionManager,
    private val otaCoordinator: Lazy<OtaRuntimeCoordinator>,
    private val telemetryEngine: Lazy<DeviceTelemetryEngine>,
    private val apkInstallManager: ApkInstallManager,
    private val broadcastRenderingEngine: BroadcastRenderingEngine
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

    init {
        startListening()
    }

    /**
     * Subscribes to connection manager's incoming WebSocket frames.
     */
    fun startListening() {
        scope.launch {
            connectionManager.incomingFrames.collect { frameJson ->
                try {
                    Log.d(TAG, "Ingested frame payload: $frameJson")
                    handleWebSocketMessage(frameJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling incoming frame", e)
                }
            }
        }
    }

    /**
     * Parsed WebSocket messages according to the target routing logic.
     */
    private fun handleWebSocketMessage(frameJson: String) {
        val root = try {
            gson.fromJson(frameJson, JsonObject::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid JSON frame: $frameJson", e)
            return
        }

        val type = root.getAsJsonPrimitive("type")?.asString?.lowercase() ?: return
        Log.i(TAG, "Routing websocket message type: $type")

        if (type == "connected" || type == "error" || type == "state.changed" || type == "pong") {
            Log.d(TAG, "Skipping legacy command processing for control message: $type")
            return
        }

        when (type) {
            "general" -> {
                // 1. General information broadcast
                val title = root.getAsJsonPrimitive("title")?.asString ?: "System Alert"
                val message = root.getAsJsonPrimitive("message")?.asString ?: ""
                val severity = root.getAsJsonPrimitive("severity")?.asString ?: "info"

                val broadcastPayload = BroadcastPayload(
                    broadcastId = "gen-${System.currentTimeMillis()}",
                    tenantId = SecurePreferences.getTenantId(context),
                    severity = severity,
                    launcherMode = BroadcastRenderingEngine.MODE_BANNER,
                    title = title,
                    message = message,
                    requiresAcknowledgement = true
                )
                broadcastRenderingEngine.dispatchBroadcast(gson.toJson(broadcastPayload))
            }
            "install" -> {
                // 2. Install application APK
                val apkUrl = root.getAsJsonPrimitive("apk_url")?.asString ?: ""
                val packageName = root.getAsJsonPrimitive("package_name")?.asString
                if (apkUrl.isNotEmpty()) {
                    downloadAndInstallApk(apkUrl, packageName)
                }
            }
            "uninstall" -> {
                // 3. Uninstall application
                val packageName = root.getAsJsonPrimitive("package_name")?.asString ?: return
                performUninstall(packageName)
            }
            "request" -> {
                // 4. Request
                Log.i(TAG, "Generic request metadata received: $frameJson")
                sendWebsocketAck("REQUEST_ACK", mapOf("status" to "acknowledged", "timestamp" to System.currentTimeMillis()))
            }
            "location_request" -> {
                // 5. Location Request
                reportCurrentLocation()
            }
            "update_request" -> {
                // 6. Update Request
                Log.i(TAG, "Immediate policy / OTA update sync scheduled")
                PolicySyncWorker.schedule(context)
                sendWebsocketAck("UPDATE_REQUEST_ACK", mapOf("status" to "sync_scheduled"))
            }
            "parameter_request" -> {
                // 7. Dynamic config parameters + decrypt keys
                val appPackage = root.getAsJsonPrimitive("app_package")?.asString ?: ""
                val parameters = root.get("parameters")
                val decryptKeys = root.get("decrypt_keys")

                if (appPackage.isNotEmpty()) {
                    val configMap = mapOf(
                        "parameters" to parameters,
                        "decrypt_keys" to decryptKeys
                    )
                    val prefs = context.getSharedPreferences("app_params_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString(appPackage, gson.toJson(configMap)).apply()
                    Log.i(TAG, "Parameters persisted for app package $appPackage")
                    sendWebsocketAck("PARAMETER_REQUEST_SUCCESS", mapOf("app_package" to appPackage))
                }
            }
            "payment_notification" -> {
                // 8. Payment Notification alert overlay
                val appName = root.getAsJsonPrimitive("app_name")?.asString ?: "Payment Service"
                val paymentPayload = root.get("payment_payload")?.toString() ?: "{}"

                val broadcastPayload = BroadcastPayload(
                    broadcastId = "pay-${System.currentTimeMillis()}",
                    tenantId = SecurePreferences.getTenantId(context),
                    severity = "success",
                    launcherMode = BroadcastRenderingEngine.MODE_BLOCKING,
                    title = "Payment Confirmed - $appName",
                    message = "Transaction Details:\n$paymentPayload",
                    requiresAcknowledgement = true
                )
                broadcastRenderingEngine.dispatchBroadcast(gson.toJson(broadcastPayload))
            }
            else -> {
                // Fallback to legacy MDM commands
                try {
                    processCommandFrame(frameJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Unmapped websocket frame action: $frameJson", e)
                }
            }
        }
    }

    private fun downloadAndInstallApk(apkUrl: String, packageName: String?) {
        scope.launch(Dispatchers.IO) {
            val stagingDir = File(context.cacheDir, "ota_staging")
            if (!stagingDir.exists()) stagingDir.mkdirs()

            val tempApkFile = File(stagingDir, "ws_install_${System.currentTimeMillis()}.apk")
            try {
                Log.d(TAG, "Downloading installation APK package from: $apkUrl")
                val okHttpClient = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder().url(apkUrl).build()
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw IOException("HTTP Error response code: ${response.code}")
                }

                val body = response.body ?: throw IOException("Empty download payload body")
                FileOutputStream(tempApkFile).use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }

                Log.d(TAG, "Download finished. Triggering native apk install sequence...")
                withContext(Dispatchers.Main) {
                    val success = apkInstallManager.installApk(tempApkFile)
                    if (success) {
                        sendWebsocketAck("INSTALL_SUCCESS", mapOf("package_name" to packageName, "apk_url" to apkUrl))
                    } else {
                        sendWebsocketAck("INSTALL_FAILED", mapOf("package_name" to packageName, "error" to "Silent install execution rejected"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed download installation process", e)
                sendWebsocketAck("INSTALL_FAILED", mapOf("package_name" to packageName, "error" to e.message))
            }
        }
    }

    private fun performUninstall(packageName: String) {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val intent = Intent(context, com.iips.launcher.core.BootReceiver::class.java).apply {
                action = "com.iips.launcher.ACTION_UNINSTALL_COMPLETE"
                putExtra("package_name", packageName)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            packageInstaller.uninstall(packageName, pendingIntent.intentSender)
            Log.i(TAG, "Silently uninstalling package: $packageName")
            sendWebsocketAck("UNINSTALL_REQUESTED", mapOf("package_name" to packageName))
        } catch (e: Exception) {
            Log.e(TAG, "Uninstallation error", e)
            sendWebsocketAck("UNINSTALL_FAILED", mapOf("package_name" to packageName, "error" to e.message))
        }
    }

    private fun reportCurrentLocation() {
        val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    sendWebsocketAck("LOCATION_REPORT", mapOf(
                        "latitude" to location.latitude,
                        "longitude" to location.longitude,
                        "accuracy" to location.accuracy,
                        "timestamp" to location.time
                    ))
                } else {
                    val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
                        com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 1000L
                    ).setMaxUpdates(1).build()

                    val callback = object : com.google.android.gms.location.LocationCallback() {
                        override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                            val loc = result.lastLocation
                            if (loc != null) {
                                sendWebsocketAck("LOCATION_REPORT", mapOf(
                                    "latitude" to loc.latitude,
                                    "longitude" to loc.longitude,
                                    "accuracy" to loc.accuracy,
                                    "timestamp" to loc.time
                                ))
                            } else {
                                sendWebsocketAck("LOCATION_REPORT_FAILED", mapOf("error" to "Device returned null coordinates"))
                            }
                        }
                    }
                    fusedLocationClient.requestLocationUpdates(
                        locationRequest, callback, android.os.Looper.getMainLooper()
                    )
                }
            }.addOnFailureListener { e ->
                sendWebsocketAck("LOCATION_REPORT_FAILED", mapOf("error" to e.message))
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission missing for location request", e)
            sendWebsocketAck("LOCATION_REPORT_FAILED", mapOf("error" to "Security permission missing"))
        }
    }

    private fun sendWebsocketAck(ackType: String, payload: Map<String, Any?>) {
        scope.launch {
            val deviceId = SecurePreferences.getDeviceId(context) ?: "UNKNOWN_DEVICE"
            val tenantId = SecurePreferences.getTenantId(context) ?: "default"
            val ackFrame = gson.toJson(mapOf(
                "type" to ackType,
                "edgeNodeId" to deviceId,
                "tenantId" to tenantId,
                "transmittedAt" to (System.currentTimeMillis() / 1000L),
                "payload" to payload
            ))
            val sent = connectionManager.transmitFrame(ackFrame)
            if (!sent) {
                val queue = SecurePreferences.getOfflineTelemetryQueue(context).toMutableList()
                queue.add(ackFrame)
                SecurePreferences.setOfflineTelemetryQueue(context, queue)
            }
        }
    }

    /**
     * Executes validated command frames securely inside isolated transaction boundaries.
     */
    fun processCommandFrame(commandJson: String) {
        scope.launch {
            try {
                val command = gson.fromJson(commandJson, MdmCommand::class.java) ?: return@launch
                if (command.id.isNullOrEmpty() || command.type.isNullOrEmpty()) {
                    Log.w(TAG, "Ingested frame is not a valid MDM command (missing id or type). Skipping execution.")
                    return@launch
                }
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

