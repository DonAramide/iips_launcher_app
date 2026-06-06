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

    /**
     * Collect SIM and cellular network info.
     */
    fun getSimInfo(context: Context): SimInfo {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val isPresent = telephonyManager.simState == TelephonyManager.SIM_STATE_READY
        val simOperator = if (isPresent) {
            val op = telephonyManager.simOperatorName
            if (op.isNullOrBlank()) {
                val netOp = telephonyManager.networkOperatorName
                if (netOp.isNullOrBlank()) "Unknown" else netOp
            } else {
                op
            }
        } else {
            "No_SIM"
        }
        val simNetworkType = if (isPresent) {
            try {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    telephonyManager.dataNetworkType
                } else {
                    telephonyManager.networkType
                }
                when (type) {
                    TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
                    TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
                    TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
                    TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
                    TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
                    TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
                    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                    TelephonyManager.NETWORK_TYPE_NR -> "5G"
                    TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
                    TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
                    TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
                    TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
                    TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
                    TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
                    TelephonyManager.NETWORK_TYPE_IDEN -> "iDEN"
                    TelephonyManager.NETWORK_TYPE_EHRPD -> "eHRPD"
                    19 -> "LTE_CA"
                    else -> "Unknown_Cellular"
                }
            } catch (e: SecurityException) {
                "Permission_Denied"
            } catch (e: Exception) {
                "Unknown"
            }
        } else {
            "No_SIM"
        }
        return SimInfo(isPresent = isPresent, simOperator = simOperator, simNetworkType = simNetworkType)
    }
}

data class SimInfo(
    val isPresent: Boolean,
    val simOperator: String,
    val simNetworkType: String
)
