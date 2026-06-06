package com.iips.launcher.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.telephony.TelephonyManager
import android.telephony.SubscriptionManager
import android.telephony.SubscriptionInfo
import android.util.Log
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

    /**
     * Collect SubscriptionInfo and device numbers.
     */
    fun getSimDetails(context: Context): List<SimDetail> {
        val details = mutableListOf<SimDetail>()
        val permissionState = context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
        if (permissionState != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return details
        }
        
        try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val activeList = subscriptionManager.activeSubscriptionInfoList
            if (activeList != null) {
                for (info in activeList) {
                    val subId = info.subscriptionId
                    val slotIndex = info.simSlotIndex
                    val displayName = info.displayName?.toString() ?: "Unknown"
                    val isOpportunistic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        info.isOpportunistic
                    } else {
                        false
                    }
                    
                    // Attempt to get phone number
                    var phoneNumber: String? = null
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            phoneNumber = subscriptionManager.getPhoneNumber(subId)
                        } else {
                            @Suppress("DEPRECATION")
                            phoneNumber = info.number
                        }
                    } catch (e: Exception) {
                        // Fallback to TelephonyManager if SubscriptionManager fails/denied
                        try {
                            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                            val subTelephony = telephonyManager.createForSubscriptionId(subId)
                            phoneNumber = subTelephony.line1Number
                        } catch (ex: Exception) {
                            phoneNumber = "Restricted"
                        }
                    }
                    
                    // Attempt to get ICCID
                    var iccid: String? = null
                    try {
                        @Suppress("DEPRECATION")
                        iccid = info.iccId
                        if (iccid.isNullOrEmpty()) {
                            iccid = "Restricted"
                        }
                    } catch (e: Exception) {
                        iccid = "Restricted"
                    }
                    
                    // Attempt to get IMSI
                    var imsi: String? = null
                    try {
                        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                        val subTelephony = telephonyManager.createForSubscriptionId(subId)
                        imsi = subTelephony.subscriberId
                        if (imsi.isNullOrEmpty()) {
                            imsi = "Restricted"
                        }
                    } catch (e: Exception) {
                        imsi = "Restricted"
                    }
                    
                    details.add(
                        SimDetail(
                            slotIndex = slotIndex,
                            subscriptionId = subId,
                            displayName = displayName,
                            isOpportunistic = isOpportunistic,
                            phoneNumber = phoneNumber ?: "Restricted",
                            iccid = iccid,
                            imsi = imsi
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("HardwareProvider", "Error reading SubscriptionInfo", e)
        }
        return details
    }
}

data class SimInfo(
    val isPresent: Boolean,
    val simOperator: String,
    val simNetworkType: String
)

data class SimDetail(
    val slotIndex: Int,
    val subscriptionId: Int,
    val displayName: String,
    val isOpportunistic: Boolean,
    val phoneNumber: String?,
    val iccid: String?,
    val imsi: String?
)
