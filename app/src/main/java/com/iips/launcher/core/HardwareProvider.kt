package com.iips.launcher.core

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
import com.iips.launcher.network.models.*

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
     * Returns system uptime in seconds.
     */
    fun getUptimeSeconds(): Long {
        return SystemClock.elapsedRealtime() / 1000
    }
}
