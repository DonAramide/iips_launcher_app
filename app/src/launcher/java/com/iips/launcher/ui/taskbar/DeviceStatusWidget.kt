package com.iips.launcher.ui.taskbar

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iips.launcher.R
import com.iips.launcher.core.HardwareProvider
import com.iips.launcher.storage.SecurePreferences

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
        val prefs = SecurePreferences.getEncryptedPrefs(context)
        val lastSync = prefs.getLong("last_telemetry_sync_time", 0L)
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
