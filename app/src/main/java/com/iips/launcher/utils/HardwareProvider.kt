package com.iips.launcher.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.telephony.TelephonyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import com.iips.launcher.config.*

/**
 * Utility class to collect hardware and system telemetry data.
 */
object HardwareProvider {

    /**
     * Collect Battery information.
     */
    fun getBatteryInfo(context: Context): BatteryInfo {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        
        val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else 0
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                        status == BatteryManager.BATTERY_STATUS_FULL
                        
        return BatteryInfo(level = batteryPct, charging = isCharging)
    }

    /**
     * Collect Network information.
     */
    fun getNetworkInfo(context: Context): NetworkInfo {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkResource = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(networkResource)
        
        val type = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "MOBILE"
            else -> "NONE"
        }
        val isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        
        return NetworkInfo(type = type, isConnected = isConnected)
    }

    /**
     * Collect SIM information.
     */
    fun getSimInfo(context: Context): SimInfo {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val isPresent = tm.simState != TelephonyManager.SIM_STATE_ABSENT
        val carrier = tm.networkOperatorName
        
        return SimInfo(
            isPresent = isPresent,
            carrier = if (isPresent) carrier else null
        )
    }

    /**
     * Collect Device State information.
     */
    fun getDeviceStateInfo(context: Context): DeviceStateInfo {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            pm.isInteractive
        } else {
            @Suppress("DEPRECATION")
            pm.isScreenOn
        }
        
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val isLocked = km.isKeyguardLocked
        
        return DeviceStateInfo(screenOn = isScreenOn, isLocked = isLocked)
    }

    /**
     * Collect Printer information.
     */
    fun getPrinterInfo(context: Context): PrinterInfo? {
        return try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            if (!bluetoothAdapter.isEnabled) return PrinterInfo(false, null, null)

            val pairedDevices = bluetoothAdapter.bondedDevices
            val printer = pairedDevices.firstOrNull { device ->
                val deviceClass = device.bluetoothClass.deviceClass
                deviceClass == BluetoothClass.Device.Major.IMAGING || 
                deviceClass == 1664 // Common printer class
            }

            if (printer != null) {
                // We can't strictly know "connected" without an active socket, 
                // but we can report that a paired printer exists.
                PrinterInfo(
                    connected = true, // Simplified: paired = "available"
                    name = printer.name,
                    battery = null // Modern printers often don't report battery via basic BT profiles
                )
            } else {
                PrinterInfo(false, null, null)
            }
        } catch (e: Exception) {
            android.util.Log.w("HardwareProvider", "Error getting printer info: ${e.message}")
            null
        }
    }
}
