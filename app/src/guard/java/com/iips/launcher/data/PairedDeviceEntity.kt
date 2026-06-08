package com.iips.launcher.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val connectivityStatus: String, // ONLINE, OFFLINE
    val securityStatus: String, // NORMAL, LOCKED, PENDING, SUSPENDED
    val lastSeen: Long,
    val pairedAt: Long,
    val branchId: String,
    val branchName: String,
    val merchantName: String,
    val managerRole: String?,
    val lastSyncAt: Long?,
    
    // Telemetry & Health additions:
    val batteryLevel: Int?,
    val networkStatus: String?, // WIFI, CELLULAR, OFFLINE
    val guardStatus: String?, // NORMAL, LOCKED, VIOLATION_PENDING, RECOVERY_PENDING
    val deviceHealthStatus: String, // HEALTHY, WARNING, CRITICAL
    val unreadAlertCount: Int
)
