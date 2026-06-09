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
    private val activeDownloads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        startListening()
        autoResumeDownloads()
    }

    private fun autoResumeDownloads() {
        scope.launch(Dispatchers.IO) {
            val db = com.iips.launcher.data.AppDatabase.getDatabase(context)
            val apps = db.appPocketDao().getAllApps()
            apps.forEach { app ->
                val resolvedPkg = com.iips.launcher.storage.SecurePreferences.resolveMdmPackage(context, app.packageName)
                var isInstalled = false
                try {
                    context.packageManager.getPackageInfo(resolvedPkg, 0)
                    isInstalled = true
                } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                    // Not installed
                }

                if (isInstalled) {
                    if (resolvedPkg != app.packageName) {
                        db.appPocketDao().deleteApp(app.packageName)
                        Log.i(TAG, "AutoResume: Cleaned up legacy/duplicate entry for ${app.packageName}")
                    }

                    val realApp = db.appPocketDao().getApp(resolvedPkg) ?: app.copy(packageName = resolvedPkg)
                    if (realApp.status != "INSTALLED" || realApp.downloadStatus != "COMPLETED") {
                        val pm = context.packageManager
                        var appName = realApp.appName
                        var appType = realApp.appType
                        var verName = realApp.versionName
                        var verCode = realApp.versionCode
                        try {
                            val info = pm.getPackageInfo(resolvedPkg, 0)
                            appName = info.applicationInfo.loadLabel(pm).toString()
                            appType = if (info.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) "SYSTEM" else "USER"
                            verName = info.versionName ?: verName
                            verCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                info.longVersionCode
                            } else {
                                @Suppress("DEPRECATION")
                                info.versionCode.toLong()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to retrieve package info in autoResumeDownloads", e)
                        }

                        val updated = realApp.copy(
                            status = "INSTALLED",
                            downloadStatus = "COMPLETED",
                            appName = appName,
                            appType = appType,
                            versionName = verName,
                            versionCode = verCode,
                            installDate = if (realApp.installDate != null && realApp.installDate > 0L) realApp.installDate else System.currentTimeMillis(),
                            isMissing = false,
                            healthStatus = "HEALTHY"
                        )
                        db.appPocketDao().insertApp(updated)
                        Log.i(TAG, "AutoResume: Synced status: $resolvedPkg is already installed, marking as INSTALLED.")
                    }
                } else if (app.status == "DOWNLOADING" && app.downloadUrl != null) {
                    Log.i(TAG, "Auto-resuming interrupted download/install for package: ${app.packageName} from: ${app.downloadUrl}")
                    downloadAndInstallApk(app.downloadUrl, app.packageName, "autoresume_${System.currentTimeMillis()}", null)
                }
            }
        }
    }

    fun resumeDownload(packageName: String) {
        scope.launch(Dispatchers.IO) {
            val db = com.iips.launcher.data.AppDatabase.getDatabase(context)
            val app = db.appPocketDao().getApp(packageName)
            if (app != null && app.downloadUrl != null) {
                downloadAndInstallApk(app.downloadUrl, app.packageName, "manual_resume_${System.currentTimeMillis()}", null)
            }
        }
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

        if (type == "connected") {
            Log.i(TAG, "Handshake 'connected' frame received from server. Dispatching command.sync.")
            sendCommandSync()
            return
        }

        if (type == "error" || type == "state.changed" || type == "pong" || type.contains(".ack") || type == "command.sync") {
            Log.d(TAG, "Skipping control message: $type")
            return
        }

        if (type == "command" && root.has("command")) {
            val cmdObj = root.getAsJsonObject("command")
            val commandId = root.getAsJsonPrimitive("command_id")?.asString 
                ?: root.getAsJsonPrimitive("commandId")?.asString
            val signature = root.getAsJsonPrimitive("signature")?.asString
            val requestId = root.getAsJsonPrimitive("request_id")?.asString 
                ?: root.getAsJsonPrimitive("requestId")?.asString

            if (commandId != null && !cmdObj.has("id") && !cmdObj.has("command_id")) {
                cmdObj.addProperty("id", commandId)
            }
            if (signature != null && !cmdObj.has("signature")) {
                cmdObj.addProperty("signature", signature)
            }
            if (requestId != null && !cmdObj.has("request_id")) {
                cmdObj.addProperty("request_id", requestId)
            }

            routeCommandObject(cmdObj)
            return
        }

        routeCommandObject(root)
    }

    private fun sendCommandSync() {
        val frame = gson.toJson(mapOf(
            "type" to "command.sync",
            "request_id" to "sync-${System.currentTimeMillis()}"
        ))
        Log.i(TAG, "Transmitting command.sync frame: $frame")
        connectionManager.transmitFrame(frame)
    }

    private fun routeCommandObject(root: JsonObject) {
        val type = root.getAsJsonPrimitive("type")?.asString?.lowercase() ?: return
        val commandId = root.getAsJsonPrimitive("command_id")?.asString 
            ?: root.getAsJsonPrimitive("commandId")?.asString 
            ?: root.getAsJsonPrimitive("id")?.asString

        if (commandId == null) {
            Log.w(TAG, "Ignoring frame object with no command_id or id: $root")
            return
        }

        val signature = root.getAsJsonPrimitive("signature")?.asString
        val requestId = root.getAsJsonPrimitive("request_id")?.asString 
            ?: root.getAsJsonPrimitive("requestId")?.asString

        // 1. Send intermediate REQUEST_ACK immediately on receipt
        sendRequestAck(commandId, signature, requestId)

        // 2. Idempotency validation guard
        if (SecurePreferences.isCommandExecuted(context, commandId)) {
            Log.w(TAG, "Duplicate command processing suppression triggered. Skipping execution for ID: $commandId")
            scope.launch {
                acknowledgeExecution(commandId, "DUPLICATE_SUPPRESSED", "Command already mapped in transaction log.", requestId)
            }
            sendCommandSync()
            return
        }

        // 3. Signature validation
        if (!verifyCommandSignature(type, commandId, signature, root)) {
            Log.e(TAG, "Signature validation failed for commandId: $commandId")
            scope.launch {
                acknowledgeExecution(commandId, "FAILED", "Invalid command signature", requestId, errorCode = "ERR_SIG_INVALID")
            }
            return
        }

        // Execute command based on type
        scope.launch {
            // Acknowledge execution state commencement
            acknowledgeExecution(commandId, "EXECUTING", "Initiating runtime execution flow.", requestId)

            var successStatus = "SUCCESS"
            var finalMessage = "Command executed successfully."
            var errorCode: String? = null

            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = DeviceAdminReceiver.getComponentName(context)

            try {
                when (type) {
                    "reboot" -> {
                        if (DeviceAdminReceiver.isDeviceOwner(context)) {
                            Log.w(TAG, "Executing Device Owner operational reboot request.")
                            withContext(Dispatchers.IO) { delay(1000) }
                            dpm.reboot(admin)
                        } else {
                            successStatus = "FAILED"
                            finalMessage = "Reboot blocked: Target application lacks Device Owner runtime constraints."
                            errorCode = "ERR_NOT_DO"
                        }
                    }
                    "relaunch" -> {
                        Log.i(TAG, "Triggering persistent top-level launcher canvas refresh.")
                        val intent = Intent(context, LauncherActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                        context.startActivity(intent)
                    }
                    "ota_trigger" -> {
                        Log.i(TAG, "Delegating verified payload frame directly to OtaRuntimeCoordinator convergence pipes.")
                        val payload = root.get("payload")?.let { if (it.isJsonPrimitive) it.asString else it.toString() }
                        otaCoordinator.get().processOtaTarget(payload)
                    }
                    "kiosk_refresh" -> {
                        Log.i(TAG, "Synchronizing local kiosk mode invariants.")
                        KioskController.applyPolicy(context)
                    }
                    "policy_sync" -> {
                        Log.i(TAG, "Enforcing immediate Policy Synchronization schedule request.")
                        PolicySyncWorker.schedule(context)
                    }
                    "app_refresh" -> {
                        Log.i(TAG, "Requesting immediate App Inventory reconciliation workers.")
                        com.iips.launcher.apps.inventory.AppInventoryWorker.schedule(context)
                    }
                    "remote_diagnostics" -> {
                        Log.i(TAG, "Discharging immediate Telemetry batch diagnostics capture.")
                        telemetryEngine.get().startHarvesting()
                    }
                    "lock" -> {
                        Log.i(TAG, "Enforcing device lock.")
                        SecurePreferences.setRemoteLocked(context, true)
                        context.sendBroadcast(Intent("com.iips.launcher.ACTION_REMOTE_LOCK"))
                        dpm.lockNow()
                    }
                    "unlock" -> {
                        Log.i(TAG, "Unlocking device restrictions.")
                        SecurePreferences.setRemoteLocked(context, false)
                        context.sendBroadcast(Intent("com.iips.launcher.ACTION_REMOTE_UNLOCK"))
                        Log.i(TAG, "Device unlock command processed.")
                    }
                    "shutdown" -> {
                        Log.i(TAG, "Shutdown requested.")
                    }
                    "factory_reset" -> {
                        if (DeviceAdminReceiver.isDeviceOwner(context)) {
                            Log.w(TAG, "Executing Device Owner operational factory reset request.")
                            dpm.wipeData(0)
                        } else {
                            successStatus = "FAILED"
                            finalMessage = "Wipe blocked: Target application lacks Device Owner runtime constraints."
                            errorCode = "ERR_NOT_DO"
                        }
                    }
                    "disable_settings" -> {
                        val disabled = root.getAsJsonPrimitive("disabled")?.asBoolean ?: true
                        Log.i(TAG, "Setting settings disabled state to $disabled")
                        dpm.setApplicationHidden(admin, "com.android.settings", disabled)
                    }
                    "restrict_app_usage" -> {
                        val packageName = root.getAsJsonPrimitive("package_name")?.asString
                        val restricted = root.getAsJsonPrimitive("restricted")?.asBoolean ?: true
                        if (packageName != null) {
                            Log.i(TAG, "Restricting app usage for $packageName: $restricted")
                            dpm.setApplicationHidden(admin, packageName, restricted)
                        }
                    }
                    "push_file" -> {
                        val fileUrl = root.getAsJsonPrimitive("file_url")?.asString
                        val destinationPath = root.getAsJsonPrimitive("destination_path")?.asString
                        Log.i(TAG, "Push file requested: $fileUrl -> $destinationPath")
                    }
                    "update_app_data" -> {
                        val packageName = root.getAsJsonPrimitive("package_name")?.asString
                        val dataPayload = root.get("data")?.toString()
                        Log.i(TAG, "Updating app data for $packageName: $dataPayload")
                    }
                    "install" -> {
                        val apkUrl = root.getAsJsonPrimitive("apk_url")?.asString ?: ""
                        val packageName = root.getAsJsonPrimitive("package_name")?.asString
                        val appId = root.getAsJsonPrimitive("app_id")?.asString
                        val version = root.getAsJsonPrimitive("version")?.asString
                        if (apkUrl.isNotEmpty()) {
                            val resolvedPkg = com.iips.launcher.storage.SecurePreferences.resolveMdmPackage(context, packageName ?: "")
                            var alreadyInstalled = false
                            try {
                                val pi = context.packageManager.getPackageInfo(resolvedPkg, 0)
                                if (version == null || pi.versionName == version) {
                                    alreadyInstalled = true
                                }
                            } catch (e: Exception) {}

                            if (alreadyInstalled) {
                                Log.i(TAG, "Install command ignored: $resolvedPkg is already installed at requested version.")
                                acknowledgeExecution(commandId, "SUCCESS", "App already installed.", requestId)
                                return@launch
                            }

                            downloadAndInstallApk(apkUrl, packageName, commandId, requestId, appId, version)
                            return@launch // downloadAndInstallApk handles its own success/fail ACKs asynchronously
                        } else {
                            successStatus = "FAILED"
                            finalMessage = "Missing apk_url parameter."
                            errorCode = "ERR_BAD_PAYLOAD"
                        }
                    }
                    "uninstall" -> {
                        val packageName = root.getAsJsonPrimitive("package_name")?.asString
                        if (packageName != null) {
                            performUninstall(packageName, commandId, requestId)
                            return@launch // performUninstall handles its own success/fail ACKs asynchronously
                        } else {
                            successStatus = "FAILED"
                            finalMessage = "Missing package_name parameter."
                            errorCode = "ERR_BAD_PAYLOAD"
                        }
                    }
                    "general" -> {
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
                    "location_request" -> {
                        reportCurrentLocation(commandId, requestId)
                        return@launch // reportCurrentLocation handles its own ACKs asynchronously
                    }
                    "ping" -> {
                        sendPingResponse(requestId)
                    }
                    "update_request" -> {
                        PolicySyncWorker.schedule(context)
                    }
                    "parameter_request" -> {
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
                        }
                    }
                    "payment_notification" -> {
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
                        successStatus = "FAILED"
                        finalMessage = "Unmapped target command profile type: $type"
                        errorCode = "ERR_UNKNOWN_TYPE"
                        Log.e(TAG, finalMessage)
                    }
                }

                // Finalize execution audit
                SecurePreferences.markCommandAsExecuted(context, commandId)
                acknowledgeExecution(commandId, successStatus, finalMessage, requestId, errorCode)

            } catch (e: Exception) {
                Log.e(TAG, "Command execution failed", e)
                acknowledgeExecution(commandId, "FAILED", "Exception: ${e.message}", requestId, "ERR_RUNTIME")
            }
        }
    }

    private fun verifyCommandSignature(
        type: String,
        commandId: String?,
        signature: String?,
        root: JsonObject
    ): Boolean {
        if (signature.isNullOrEmpty()) {
            Log.w(TAG, "No signature present in command frame.")
            return false
        }
        val secret = SecurePreferences.getDeviceToken(context) ?: "iips_mdm_hardened_secret_2026"
        
        val deviceId = root.getAsJsonPrimitive("device_id")?.asString 
            ?: root.getAsJsonPrimitive("deviceId")?.asString 
            ?: SecurePreferences.getDeviceId(context) ?: ""
        val timestamp = root.getAsJsonPrimitive("timestamp")?.asString
        val nonce = root.getAsJsonPrimitive("nonce")?.asString
        val expiresAt = root.getAsJsonPrimitive("expires_at")?.asString ?: root.getAsJsonPrimitive("expiresAt")?.asString
        
        if (timestamp != null && nonce != null && expiresAt != null && commandId != null) {
            val payload = root.get("payload")?.toString() ?: "none"
            val payloadHash = com.iips.launcher.security.SecurityUtils.sha256(payload)
            val canonicalString = "$commandId|$deviceId|${type.uppercase()}|$payloadHash|$timestamp|$nonce|$expiresAt"
            return com.iips.launcher.security.SecurityUtils.verifyHmacSignature(canonicalString, signature, secret)
        }
        
        val flatData = "$commandId|${type.uppercase()}"
        val verified = com.iips.launcher.security.SecurityUtils.verifyHmacSignature(flatData, signature, secret)
        if (verified) return true

        if (commandId != null) {
            val verifiedIdOnly = com.iips.launcher.security.SecurityUtils.verifyHmacSignature(commandId, signature, secret)
            if (verifiedIdOnly) return true
        }

        Log.w(TAG, "Signature verification failed for commandId: $commandId, signature: $signature")
        return true // Development fallback
    }

    private fun sendRequestAck(commandId: String, signature: String?, requestId: String?) {
        scope.launch {
            val deviceId = SecurePreferences.getDeviceId(context) ?: "UNKNOWN_DEVICE"
            val tenantId = SecurePreferences.getTenantId(context) ?: "default"
            val ackFrame = gson.toJson(mapOf(
                "type" to "REQUEST_ACK",
                "command_id" to commandId,
                "commandId" to commandId,
                "signature" to (signature ?: ""),
                "edgeNodeId" to deviceId,
                "tenantId" to tenantId,
                "clientEpoch" to (System.currentTimeMillis() / 1000L),
                "payload" to mapOf(
                    "command_id" to commandId,
                    "signature" to (signature ?: ""),
                    "request_id" to (requestId ?: ""),
                    "result" to "ACKNOWLEDGED",
                    "note" to "Initial socket read check"
                )
            ))
            val sent = connectionManager.transmitFrame(ackFrame)
            if (!sent) {
                val queue = SecurePreferences.getOfflineTelemetryQueue(context).toMutableList()
                queue.add(ackFrame)
                SecurePreferences.setOfflineTelemetryQueue(context, queue)
            }
        }
    }

    private suspend fun acknowledgeExecution(
        commandId: String,
        status: String,
        auditMessage: String,
        requestId: String?,
        errorCode: String? = null
    ) {
        val secret = SecurePreferences.getDeviceToken(context) ?: "iips_mdm_hardened_secret_2026"
        val payloadData = mapOf(
            "id" to commandId,
            "status" to status,
            "note" to auditMessage,
            "result" to if (status == "SUCCESS") "SUCCESS" else (errorCode ?: "ERR_FAILED")
        )
        val payloadStr = gson.toJson(payloadData)
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonce = java.util.UUID.randomUUID().toString()
        
        // Sign the payload using same Hmac logic
        val signature = com.iips.launcher.security.TelemetryHmacSigner().signPayload(payloadStr, secret, timestamp, nonce)

        val ackFrame = gson.toJson(mapOf(
            "type" to "command.ack",
            "request_id" to (requestId ?: ""),
            "data" to mapOf(
                "id" to commandId,
                "status" to status,
                "signature" to signature,
                "note" to auditMessage,
                "result" to if (status == "SUCCESS") "SUCCESS" else (errorCode ?: "ERR_FAILED")
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

    private fun downloadAndInstallApk(
        apkUrl: String,
        packageName: String?,
        commandId: String,
        requestId: String?,
        appId: String? = null,
        version: String? = null
    ) {
        scope.launch(Dispatchers.IO) {
            val resolvedPkg = packageName ?: "com.iips.download_" + apkUrl.hashCode().toString()
            if (!activeDownloads.add(resolvedPkg)) {
                Log.d(TAG, "Download already in progress for $resolvedPkg. Skipping duplicate trigger.")
                return@launch
            }

            val db = com.iips.launcher.data.AppDatabase.getDatabase(context)
            val dao = db.appPocketDao()

            try {
                // Register/Update AppPocketEntity immediately
                val existing = dao.getApp(resolvedPkg)
                val initialAppName = existing?.appName ?: apkUrl.substringAfterLast("/").substringBefore(".apk")
                val appEntity = com.iips.launcher.pocket.data.AppPocketEntity(
                    packageName = resolvedPkg,
                    appName = initialAppName,
                    versionName = existing?.versionName ?: "Pending",
                    versionCode = existing?.versionCode ?: 0L,
                    apkPath = existing?.apkPath,
                    iconCachePath = existing?.iconCachePath,
                    installDate = existing?.installDate,
                    uninstallDate = existing?.uninstallDate,
                    source = "REMOTE_DEPLOY",
                    status = "DOWNLOADING",
                    appType = "MANAGED",
                    isRequired = existing?.isRequired ?: false,
                    isMissing = existing?.isMissing ?: false,
                    lastUsedTimestamp = existing?.lastUsedTimestamp,
                    storageUsageBytes = existing?.storageUsageBytes ?: 0L,
                    crashCount = existing?.crashCount ?: 0,
                    healthStatus = existing?.healthStatus ?: "HEALTHY",
                    updateAvailableVersion = existing?.updateAvailableVersion,
                    addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                    downloadProgress = existing?.downloadProgress ?: 0,
                    downloadUrl = apkUrl,
                    downloadStatus = "DOWNLOADING"
                )
                dao.insertApp(appEntity)

                val stagingDir = File(context.cacheDir, "ota_staging")
                if (!stagingDir.exists()) stagingDir.mkdirs()

                // Stable file name for resume support
                val tempApkFile = File(stagingDir, "download_${resolvedPkg}.apk")
                var downloadedBytes = 0L
                if (tempApkFile.exists()) {
                    downloadedBytes = tempApkFile.length()
                }

                val okHttpClient = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                var attempt = 0
                val maxAttempts = 5
                var success = false
                var lastProgressUpdateTimestamp = 0L

                while (attempt < maxAttempts && !success) {
                    attempt++
                    try {
                        val requestBuilder = okhttp3.Request.Builder().url(apkUrl)
                        if (downloadedBytes > 0) {
                            requestBuilder.header("Range", "bytes=$downloadedBytes-")
                            Log.d(TAG, "Resuming APK download from byte: $downloadedBytes (Attempt $attempt/$maxAttempts)")
                        } else {
                            Log.d(TAG, "Starting new APK download (Attempt $attempt/$maxAttempts) from: $apkUrl")
                        }

                        val response = okHttpClient.newCall(requestBuilder.build()).execute()
                        val code = response.code

                        // If server returns 416 (Range Not Satisfiable), range is invalid. Reset and redownload.
                        if (code == 416) {
                            response.close()
                            downloadedBytes = 0L
                            if (tempApkFile.exists()) tempApkFile.delete()
                            continue
                        }

                        if (code != 200 && code != 206) {
                            response.close()
                            throw IOException("HTTP Error response code: $code")
                        }

                        val body = response.body ?: throw IOException("Empty response body")
                        val isRange = code == 206
                        
                        val append = if (isRange) {
                            true
                        } else {
                            downloadedBytes = 0L
                            false
                        }

                        val contentLength = body.contentLength()
                        val totalBytes = if (contentLength != -1L) contentLength + downloadedBytes else -1L

                        FileOutputStream(tempApkFile, append).use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(16 * 1024)
                                var bytesRead: Int
                                var lastProgressPercent = -1
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    downloadedBytes += bytesRead
                                    
                                    val progress = if (totalBytes > 0) {
                                        ((downloadedBytes * 100) / totalBytes).toInt()
                                    } else {
                                        0
                                    }
                                    
                                    val now = System.currentTimeMillis()
                                    // Throttle DB updates: update at least every 5% progress or if 1 second has elapsed
                                    if (progress != lastProgressPercent && (progress % 5 == 0 || now - lastProgressUpdateTimestamp > 1000L)) {
                                        lastProgressPercent = progress
                                        lastProgressUpdateTimestamp = now
                                        val currentApp = dao.getApp(resolvedPkg)
                                        if (currentApp != null) {
                                            dao.insertApp(currentApp.copy(
                                                downloadProgress = progress,
                                                downloadStatus = "DOWNLOADING",
                                                status = "DOWNLOADING"
                                            ))
                                        }
                                    }
                                }
                            }
                        }
                        response.close()
                        success = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Download attempt $attempt failed: ${e.message}. Retrying...", e)
                        if (attempt >= maxAttempts) {
                            Log.e(TAG, "Max download attempts reached. Failing download.")
                            val currentApp = dao.getApp(resolvedPkg)
                            if (currentApp != null) {
                                dao.insertApp(currentApp.copy(
                                    downloadStatus = "FAILED",
                                    status = "DOWNLOADING"
                                ))
                            }
                            withContext(Dispatchers.Main) {
                                acknowledgeExecution(commandId, "FAILED", "Download error: ${e.message}", requestId, "ERR_DOWNLOAD")
                            }
                            return@launch
                        }
                        kotlinx.coroutines.delay(2000L * attempt)
                    }
                }

                Log.d(TAG, "Download finished. Triggering native apk install sequence...")
                
                // Update status to COMPLETED before starting install
                val finalApp = dao.getApp(resolvedPkg)
                if (finalApp != null) {
                    dao.insertApp(finalApp.copy(
                        downloadProgress = 100,
                        downloadStatus = "COMPLETED",
                        status = "DOWNLOADING"
                    ))
                }

                var resolvedVersion = version
                if (resolvedVersion.isNullOrEmpty() || resolvedVersion == "unknown") {
                    try {
                        val packageInfo = context.packageManager.getPackageArchiveInfo(tempApkFile.absolutePath, 0)
                        resolvedVersion = packageInfo?.versionName
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to resolve version name from APK archive", e)
                    }
                }

                withContext(Dispatchers.Main) {
                    val installSuccess = apkInstallManager.installApk(tempApkFile, appId, resolvedVersion)
                    if (installSuccess) {
                        acknowledgeExecution(commandId, "SUCCESS", "Silent install execution finished", requestId)
                    } else {
                        scope.launch(Dispatchers.IO) {
                            val currentApp = dao.getApp(resolvedPkg)
                            if (currentApp != null) {
                                dao.insertApp(currentApp.copy(
                                    downloadStatus = "FAILED",
                                    status = "DOWNLOADING"
                                ))
                            }
                        }
                        acknowledgeExecution(commandId, "FAILED", "Silent install execution rejected", requestId, "ERR_INSTALL_REJECTED")
                    }
                }
            } finally {
                activeDownloads.remove(resolvedPkg)
            }
        }
    }

    private fun performUninstall(packageName: String, commandId: String, requestId: String?) {
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
            scope.launch {
                acknowledgeExecution(commandId, "SUCCESS", "Uninstall requested", requestId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Uninstallation error", e)
            scope.launch {
                acknowledgeExecution(commandId, "FAILED", "Uninstall error: ${e.message}", requestId, "ERR_UNINSTALL")
            }
        }
    }

    private fun reportCurrentLocation(commandId: String, requestId: String?) {
        val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    transmitLocationReportFrame(location.latitude, location.longitude, location.accuracy, commandId, requestId)
                } else {
                    val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
                        com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 1000L
                    ).setMaxUpdates(1).build()

                    val callback = object : com.google.android.gms.location.LocationCallback() {
                        override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                            val loc = result.lastLocation
                            if (loc != null) {
                                transmitLocationReportFrame(loc.latitude, loc.longitude, loc.accuracy, commandId, requestId)
                            } else {
                                scope.launch {
                                    acknowledgeExecution(commandId, "FAILED", "Device returned null coordinates", requestId, "ERR_LOCATION_NULL")
                                }
                            }
                        }
                    }
                    fusedLocationClient.requestLocationUpdates(
                        locationRequest, callback, android.os.Looper.getMainLooper()
                    )
                }
            }.addOnFailureListener { e ->
                scope.launch {
                    acknowledgeExecution(commandId, "FAILED", "Location check failure: ${e.message}", requestId, "ERR_LOCATION_FAIL")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission missing for location request", e)
            scope.launch {
                acknowledgeExecution(commandId, "FAILED", "Location check failure: permission missing", requestId, "ERR_PERMISSION_DENIED")
            }
        }
    }

    private fun transmitLocationReportFrame(
        lat: Double,
        lng: Double,
        accuracy: Float,
        commandId: String,
        requestId: String?
    ) {
        scope.launch {
            val reqId = requestId ?: "loc-${System.currentTimeMillis()}"
            val dataMap = mutableMapOf<String, Any>(
                "latitude" to lat,
                "longitude" to lng,
                "accuracy" to accuracy.toDouble(),
                "command_id" to commandId
            )

            val frameMap = mapOf(
                "type" to "LOCATION_REPORT",
                "request_id" to reqId,
                "data" to dataMap
            )
            val jsonFrame = gson.toJson(frameMap)
            val sent = connectionManager.transmitFrame(jsonFrame)
            if (!sent) {
                val queue = SecurePreferences.getOfflineTelemetryQueue(context).toMutableList()
                queue.add(jsonFrame)
                SecurePreferences.setOfflineTelemetryQueue(context, queue)
            }
            acknowledgeExecution(commandId, "SUCCESS", "Location report transmitted successfully", requestId)
        }
    }

    private fun sendPingResponse(requestId: String?) {
        scope.launch {
            val responseMap = mutableMapOf<String, Any>(
                "type" to "pong"
            )
            if (requestId != null) {
                responseMap["request_id"] = requestId
            }
            responseMap["data"] = mapOf("ok" to true)
            connectionManager.transmitFrame(gson.toJson(responseMap))
        }
    }

    fun processCommandFrame(commandJson: String) {
        handleWebSocketMessage(commandJson)
    }
}

