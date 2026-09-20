package com.iips.launcher.ui.taskbar

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.iips.launcher.R
import com.iips.launcher.core.HardwareProvider
import com.iips.launcher.network.models.HeartbeatRequest
import com.iips.launcher.security.TelemetryHmacSigner
import com.iips.launcher.storage.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

class DeviceStatusDialogFragment(
    private val onAdminSettingsClick: () -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_device_status, null)

        // WiFi Status
        val txtWifiVal: TextView = view.findViewById(R.id.txt_wifi_val)
        val networkInfo = HardwareProvider.getNetworkInfo(context)
        if (networkInfo.isConnected) {
            if (networkInfo.type == "WIFI") {
                txtWifiVal.text = "WiFi Connected"
            } else {
                val simInfo = HardwareProvider.getSimInfo(context)
                txtWifiVal.text = "Cellular (${simInfo.simOperator})"
            }
        } else {
            txtWifiVal.text = "Disconnected"
        }

        // Battery Status
        val txtBatteryVal: TextView = view.findViewById(R.id.txt_battery_val)
        val batteryInfo = HardwareProvider.getBatteryInfo(context)
        txtBatteryVal.text = "${batteryInfo.level}%${if (batteryInfo.charging) " (Charging)" else ""}"

        // Last Sync
        val txtSyncVal: TextView = view.findViewById(R.id.txt_sync_val)
        val lastSync = SecurePreferences.getLastHeartbeatTime(context)
        txtSyncVal.text = formatRelativeTime(lastSync)

        // Guard Connected
        val txtGuardVal: TextView = view.findViewById(R.id.txt_guard_val)
        val deviceState = SecurePreferences.getDeviceState(context)
        if (deviceState == SecurePreferences.STATE_ACTIVE) {
            txtGuardVal.text = "Active & Secured"
            txtGuardVal.setTextColor(context.getColor(android.R.color.holo_green_dark))
        } else if (deviceState == SecurePreferences.STATE_LOCKED) {
            txtGuardVal.text = "Locked (Violation)"
            txtGuardVal.setTextColor(context.getColor(android.R.color.holo_red_dark))
        } else {
            txtGuardVal.text = "Monitoring ($deviceState)"
            txtGuardVal.setTextColor(context.getColor(android.R.color.holo_orange_dark))
        }

        // Quasar Cloud Handshake Status Row
        val rowQuasar: View = view.findViewById(R.id.row_quasar_status)
        val imgCloud: ImageView = view.findViewById(R.id.img_quasar_cloud_status)
        val txtQuasarVal: TextView = view.findViewById(R.id.txt_quasar_val)
        val txtQuasarSub: TextView = view.findViewById(R.id.txt_quasar_sub)

        val isCloudConnected = SecurePreferences.getLastHeartbeatSuccess(context)
        val cloudMsg = SecurePreferences.getLastHeartbeatMessage(context)

        updateQuasarUi(
            imgCloud,
            txtQuasarVal,
            txtQuasarSub,
            txtSyncVal,
            isCloudConnected,
            cloudMsg,
            lastSync
        )

        rowQuasar.setOnClickListener {
            performQuasarHandshake(context, imgCloud, txtQuasarVal, txtQuasarSub, txtSyncVal)
        }

        // Admin settings button
        val btnAdminSettings: MaterialButton = view.findViewById(R.id.btn_admin_settings)
        btnAdminSettings.setOnClickListener {
            dismiss()
            onAdminSettingsClick()
        }

        return MaterialAlertDialogBuilder(context)
            .setView(view)
            .create()
    }

    private fun updateQuasarUi(
        imgCloud: ImageView,
        txtVal: TextView,
        txtSub: TextView,
        txtSyncVal: TextView,
        isSuccess: Boolean,
        message: String,
        timestamp: Long
    ) {
        val greenColor = Color.parseColor("#22C55E")
        val redColor = Color.parseColor("#EF4444")

        if (isSuccess) {
            imgCloud.imageTintList = ColorStateList.valueOf(greenColor)
            txtVal.text = "Connected"
            txtVal.setTextColor(greenColor)
            txtSub.text = "Handshake OK • Tap to re-test"
        } else {
            imgCloud.imageTintList = ColorStateList.valueOf(redColor)
            txtVal.text = message
            txtVal.setTextColor(redColor)
            txtSub.text = "Tap to establish handshake"
        }
        txtSyncVal.text = formatRelativeTime(timestamp)
    }

    private fun performQuasarHandshake(
        context: Context,
        imgCloud: ImageView,
        txtVal: TextView,
        txtSub: TextView,
        txtSyncVal: TextView
    ) {
        val yellowColor = Color.parseColor("#EAB308")
        txtVal.text = "Connecting..."
        txtVal.setTextColor(yellowColor)
        txtSub.text = "Sending heartbeat to Quasar..."
        imgCloud.imageTintList = ColorStateList.valueOf(yellowColor)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val token = SecurePreferences.getDeviceToken(context)
                val deviceId = SecurePreferences.getDeviceId(context)

                if (token.isNullOrBlank() || deviceId.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        val msg = "Unregistered Device"
                        SecurePreferences.setLastHeartbeatStatus(context, false, msg)
                        updateQuasarUi(imgCloud, txtVal, txtSub, txtSyncVal, false, msg, System.currentTimeMillis())
                        Toast.makeText(context, "Quasar Handshake: Device not registered", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                var seq = SecurePreferences.getNextTelemetrySeq(context)

                val batteryInfo = HardwareProvider.getBatteryInfo(context)
                val networkInfo = HardwareProvider.getNetworkInfo(context)
                val uptime = HardwareProvider.getUptimeSeconds()
                val simInfo = HardwareProvider.getSimInfo(context)
                val simDetails = HardwareProvider.getSimDetails(context)
                val tenantId = SecurePreferences.getTenantId(context) ?: "default"

                val baseUrl = SecurePreferences.getConfigUrl(context)
                val normalizedUrl = SecurePreferences.normalizeBackendUrl(baseUrl)
                val gson = Gson()
                val hmacSigner = TelemetryHmacSigner()
                val okHttpClient = OkHttpClient()

                fun buildAndExecuteRequest(sequenceNum: Long): Pair<Boolean, Int> {
                    val heartbeat = HeartbeatRequest(
                        deviceId = deviceId,
                        tenantId = tenantId,
                        telemetrySeq = sequenceNum,
                        batteryLevel = batteryInfo.level,
                        isCharging = batteryInfo.charging,
                        networkStatus = networkInfo.type,
                        uptime = uptime,
                        location = null,
                        deviceTime = System.currentTimeMillis(),
                        isSimPresent = simInfo.isPresent,
                        simOperator = simInfo.simOperator,
                        simNetworkType = simInfo.simNetworkType,
                        simDetails = simDetails,
                        serialNumber = deviceId
                    )

                    val timestamp = (System.currentTimeMillis() / 1000).toString()
                    val nonce = UUID.randomUUID().toString()
                    val jsonPayload = gson.toJson(heartbeat)
                    val requestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                    val signature = hmacSigner.signPayload(jsonPayload, token, timestamp, nonce)

                    val request = Request.Builder()
                        .url("${normalizedUrl}device/heartbeat")
                        .post(requestBody)
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("X-IIPS-Signature", signature)
                        .addHeader("X-IIPS-Timestamp", timestamp)
                        .addHeader("X-IIPS-Nonce", nonce)
                        .build()

                    val response = okHttpClient.newCall(request).execute()
                    val success = response.isSuccessful
                    val code = response.code
                    response.close()
                    return Pair(success, code)
                }

                var (success, statusCode) = buildAndExecuteRequest(seq)

                // If 409 Conflict (Stale sequence counter on server), jump sequence forward and retry once
                if (!success && statusCode == 409) {
                    SecurePreferences.advanceTelemetrySeq(context, 100000L)
                    val retrySeq = SecurePreferences.getNextTelemetrySeq(context)
                    val retryResult = buildAndExecuteRequest(retrySeq)
                    success = retryResult.first
                    statusCode = retryResult.second
                }

                withContext(Dispatchers.Main) {
                    if (success) {
                        val msg = "Connected"
                        val now = System.currentTimeMillis()
                        SecurePreferences.setLastHeartbeatStatus(context, true, msg)
                        updateQuasarUi(imgCloud, txtVal, txtSub, txtSyncVal, true, msg, now)
                        Toast.makeText(context, "Quasar Handshake Successful!", Toast.LENGTH_SHORT).show()
                    } else {
                        val msg = when (statusCode) {
                            401 -> "401 Unauthorized"
                            409 -> "409 Sequence Skew"
                            else -> "Error $statusCode"
                        }
                        SecurePreferences.setLastHeartbeatStatus(context, false, msg)
                        updateQuasarUi(imgCloud, txtVal, txtSub, txtSyncVal, false, msg, System.currentTimeMillis())
                        Toast.makeText(context, "Quasar Handshake Failed: $msg", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val msg = "Connection Error"
                    SecurePreferences.setLastHeartbeatStatus(context, false, msg)
                    updateQuasarUi(imgCloud, txtVal, txtSub, txtSyncVal, false, msg, System.currentTimeMillis())
                    Toast.makeText(context, "Quasar Handshake Failed: Connection Error", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun formatRelativeTime(timestamp: Long): String {
        if (timestamp <= 0L) return "Never"
        val diffMs = System.currentTimeMillis() - timestamp
        if (diffMs < 0) return "Just now"
        val diffSec = diffMs / 1000
        if (diffSec < 60) return "Just now"
        val diffMin = diffSec / 60
        if (diffMin < 60) return "${diffMin}m ago"
        val diffHours = diffMin / 60
        if (diffHours < 24) return "${diffHours}h ago"
        val diffDays = diffHours / 24
        return "${diffDays}d ago"
    }
}
