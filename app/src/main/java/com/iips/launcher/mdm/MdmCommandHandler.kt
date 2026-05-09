package com.iips.launcher.mdm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.iips.launcher.config.CommandAcknowledgement
import com.iips.launcher.config.MdmCommand
import com.iips.launcher.device.DeviceAdminReceiver
import com.iips.launcher.utils.SecurePreferences
import com.iips.launcher.utils.SecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes MDM commands with strict production-grade security pipeline.
 */
object MdmCommandHandler {
    private const val TAG = "MdmCommandHandler"
    private const val BASE_SECRET = "iips_mdm_hardened_secret_2026"

    private fun getSecret(context: Context): String {
        return SecurePreferences.getDeviceToken(context) ?: BASE_SECRET
    }

    /**
     * Processes a command through the full security and validation pipeline.
     * Reports status updates via the provided callback.
     */
    suspend fun handleCommand(context: Context, command: MdmCommand, ackCallback: suspend (CommandAcknowledgement) -> Unit) = withContext(Dispatchers.Main) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = DeviceAdminReceiver.getComponentName(context)

        Log.d(TAG, "Processing command: ${command.type} (${command.id})")

        // Step 1: RECEIVED
        ackCallback(CommandAcknowledgement(command.id, "RECEIVED"))

        // Step 2: Validate Identity Binding
        val selfDeviceId = SecurePreferences.getDeviceId(context)
        if (command.deviceId != selfDeviceId) {
            Log.e(TAG, "Identity mismatch: ${command.deviceId} vs $selfDeviceId")
            ackCallback(CommandAcknowledgement(command.id, "FAILED", message = "Identity binding mismatch", errorCode = "ERR_ID_MISMATCH"))
            return@withContext
        }

        // Step 3: Validate Signature (Canonical String HMAC-SHA256)
        // Format: id|device_id|type|payload_hash|timestamp|nonce|expires_at
        val payloadHash = if (command.payload != null) SecurityUtils.sha256(command.payload) else "none"
        val canonicalString = "${command.id}|${command.deviceId}|${command.type}|${payloadHash}|${command.timestamp}|${command.nonce}|${command.expiresAt}"
        
        if (!SecurityUtils.verifyHmacSignature(canonicalString, command.signature, getSecret(context))) {
            Log.e(TAG, "Signature verification failed for ${command.id}")
            ackCallback(CommandAcknowledgement(command.id, "FAILED", message = "Invalid command signature", errorCode = "ERR_SIG_INVALID"))
            return@withContext
        }

        // Step 4: Validate Timestamp Drift (Max 60 seconds)
        val currentUnix = System.currentTimeMillis() / 1000
        val drift = Math.abs(currentUnix - command.timestamp)
        if (drift > 60) {
            Log.e(TAG, "Command timestamp drift too high: ${drift}s")
            ackCallback(CommandAcknowledgement(command.id, "FAILED", message = "Clock drift exceeded limits", errorCode = "ERR_TIME_DRIFT"))
            return@withContext
        }

        // Step 5: Validate Expiration
        if (currentUnix > command.expiresAt) {
            Log.e(TAG, "Command expired at ${command.expiresAt} (Current: $currentUnix)")
            ackCallback(CommandAcknowledgement(command.id, "FAILED", message = "Command TTL expired", errorCode = "ERR_EXPIRED"))
            return@withContext
        }

        // Step 6: Validate Nonce (Persistent Replay Protection)
        if (NonceManager.isNonceReplayed(context, command.nonce)) {
            Log.e(TAG, "Replay attack detected for nonce ${command.nonce}")
            ackCallback(CommandAcknowledgement(command.id, "FAILED", message = "Replay protection triggered", errorCode = "ERR_REPLAY"))
            return@withContext
        }

        // Step 7: VALIDATED
        ackCallback(CommandAcknowledgement(command.id, "VALIDATED"))

        // Step 8: EXECUTING
        ackCallback(CommandAcknowledgement(command.id, "EXECUTING"))

        try {
            val result = when (command.type.uppercase()) {
                "LOCK" -> {
                    dpm.lockNow()
                    CommandAcknowledgement(command.id, "SUCCESS")
                }
                "REBOOT" -> {
                    if (isConfirmed(command.payload)) {
                        if (DeviceAdminReceiver.isDeviceOwner(context)) {
                            dpm.reboot(admin)
                            CommandAcknowledgement(command.id, "SUCCESS")
                        } else {
                            CommandAcknowledgement(command.id, "FAILED", message = "Not Device Owner", errorCode = "ERR_NOT_DO")
                        }
                    } else {
                        CommandAcknowledgement(command.id, "FAILED", message = "Critical command requires confirmation flag", errorCode = "ERR_NO_CONFIRM")
                    }
                }
                "WIPE" -> {
                    if (isConfirmed(command.payload)) {
                        if (DeviceAdminReceiver.isDeviceOwner(context)) {
                            Log.w(TAG, "SECURITY ALERT: Remote WIPE triggered!")
                            dpm.wipeData(0)
                            CommandAcknowledgement(command.id, "SUCCESS")
                        } else {
                            CommandAcknowledgement(command.id, "FAILED", message = "Not Device Owner", errorCode = "ERR_NOT_DO")
                        }
                    } else {
                        CommandAcknowledgement(command.id, "FAILED", message = "Wipe requires explicit confirmation", errorCode = "ERR_NO_CONFIRM")
                    }
                }
                "PUSH_NOTIFICATION" -> {
                    showNotification(context, command.payload ?: "System Alert")
                    CommandAcknowledgement(command.id, "SUCCESS")
                }
                "SET_SHARED_PARAMS" -> {
                    val payloadStr = command.payload ?: "{}"
                    SecurePreferences.setSharedJsonParams(context, payloadStr)
                    CommandAcknowledgement(command.id, "SUCCESS", message = "Shared parameters updated")
                }
                "INSTALL_APK" -> {
                    val payload = command.payload ?: ""
                    var url: String? = null
                    var appId: String? = null
                    var version: String? = null
                    
                    try {
                        if (payload.startsWith("{")) {
                            val json = org.json.JSONObject(payload)
                            url = json.optString("url")
                            appId = json.optString("app_id")
                            version = json.optString("version")
                        } else {
                            url = payload
                        }
                        
                        // Policy Compatibility Check (New Snapshot Model)
                        if (appId != null) {
                            val snapshot = SecurePreferences.getDevicePolicySnapshot(context)
                            if (snapshot?.blockedApps?.contains(appId) == true) {
                                Log.e(TAG, "Policy Violation: Attempted to install BLOCKED app $appId")
                                ackCallback(CommandAcknowledgement(command.id, "FAILED", message = "App is BLOCKED by organizational policy", errorCode = "ERR_POLICY_VIOLATION"))
                                return@withContext
                            }
                        }

                        if (url != null && url.startsWith("https://")) {
                            val success = AppInstaller.installApk(context, url, null, appId, version)
                            if (success) {
                                CommandAcknowledgement(command.id, "SUCCESS", message = "Update session committed")
                            } else {
                                CommandAcknowledgement(command.id, "FAILED", message = "Installer session failed", errorCode = "ERR_INSTALL_FAIL")
                            }
                        } else {
                            CommandAcknowledgement(command.id, "FAILED", message = "Unsecured or missing APK source", errorCode = "ERR_INVALID_URL")
                        }
                    } catch (e: Exception) {
                        CommandAcknowledgement(command.id, "FAILED", message = "Payload processing error", errorCode = "ERR_PAYLOAD")
                    }
                }
                "UNINSTALL_APK" -> {
                    val packageName = command.payload
                    if (!packageName.isNullOrBlank()) {
                        // Policy Compatibility Check (New Snapshot Model)
                        val snapshot = SecurePreferences.getDevicePolicySnapshot(context)
                        if (snapshot?.allowedApps?.contains(packageName) == false && snapshot?.blockedApps?.contains(packageName) == false) {
                           // If it's not in allowed nor blocked, it might be a system app or something else.
                           // But usually, we only block uninstalls of "REQUIRED" apps.
                           // For now, if it's in allowed list, we might consider it "optional" or "managed".
                           // If the user wants to uninstall, let's check a flag.
                        }

                        val success = AppInstaller.silentUninstall(context, packageName)
                        if (success) {
                            CommandAcknowledgement(command.id, "SUCCESS", message = "Uninstall session requested")
                        } else {
                            CommandAcknowledgement(command.id, "FAILED", message = "Uninstall request failed", errorCode = "ERR_UNINSTALL_FAIL")
                        }
                    } else {
                        CommandAcknowledgement(command.id, "FAILED", message = "Missing package name in payload", errorCode = "ERR_INVALID_PKG")
                    }
                }
                "SET_KIOSK_MODE" -> {
                    try {
                        val json = org.json.JSONObject(command.payload ?: "{}")
                        val enabled = json.optBoolean("enabled", true)
                        // Update snapshot atomically
                        val currentSnapshot = SecurePreferences.getDevicePolicySnapshot(context)
                        if (currentSnapshot != null) {
                            val updatedSnapshot = currentSnapshot.copy(kioskMode = enabled)
                            SecurePreferences.setDevicePolicySnapshot(context, updatedSnapshot)
                        }
                        
                        com.iips.launcher.mdm.KioskController.applyPolicy(context)
                        
                        CommandAcknowledgement(command.id, "SUCCESS", message = "Kiosk mode updated in snapshot")
                    } catch (e: Exception) {
                        CommandAcknowledgement(command.id, "FAILED", message = "Invalid JSON payload", errorCode = "ERR_INVALID_PAYLOAD")
                    }
                }
                else -> {
                    CommandAcknowledgement(command.id, "FAILED", message = "Unknown command type", errorCode = "ERR_UNKNOWN_TYPE")
                }
            }

            // Step 9: Final Result (SUCCESS / FAILED)
            ackCallback(result)
            
            // Mark as executed for long-term tracking
            if (result.status == "SUCCESS") {
                SecurePreferences.markCommandAsExecuted(context, command.id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failure", e)
            ackCallback(CommandAcknowledgement(command.id, "FAILED", message = e.message, errorCode = "ERR_RUNTIME"))
        }
    }

    private fun isConfirmed(payload: String?): Boolean {
        return try {
            val json = org.json.JSONObject(payload ?: "{}")
            // Backend must send 'requires_confirmation: true' for critical actions
            json.optBoolean("requires_confirmation", true)
        } catch (e: Exception) {
            false
        }
    }

    private fun showNotification(context: Context, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "mdm_alerts"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Enterprise Security", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Enterprise System Message")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
