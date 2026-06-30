package com.iips.launcher.network.models

data class PairingTokenResponse(
    val pairingToken: String,
    val userCode: String?,
    val expiresAt: String
)

data class GuardPairingRequest(
    val pairingToken: String,
    val pairingMethod: String // QR_SCAN, MANUAL_CODE, NFC
)

data class GuardPairingResponse(
    val deviceId: String,
    val deviceName: String,
    val branchId: String,
    val branchName: String,
    val merchantName: String,
    val status: String, // Keep for backward compatibility/pairing APIs
    val connectivityStatus: String, // ONLINE, OFFLINE
    val securityStatus: String, // NORMAL, LOCKED, PENDING, SUSPENDED
    val lastSeen: Long,
    val pairedAt: Long,
    val managerRole: String? = null,
    val lastSyncAt: Long? = null,
    val batteryLevel: Int? = null,
    val networkStatus: String? = null,
    val guardStatus: String? = null,
    val deviceHealthStatus: String = "HEALTHY", // HEALTHY, WARNING, CRITICAL
    val unreadAlertCount: Int = 0,
    val lat: Double? = null,
    val lng: Double? = null,
    val isSimPresent: Boolean? = null,
    val simOperator: String? = null,
    val simNetworkType: String? = null,
    val uptime: Long? = null
)

data class PairingStatusRequest(
    val status: String // ACTIVE, SUSPENDED, REVOKED
)

data class PairingStatusResponse(
    val deviceId: String,
    val status: String
)

data class PairingQrPayload(
    val pairingToken: String,
    val userCode: String?,
    val expiresAt: Long,
    val deviceId: String,
    val deviceName: String,
    val backendUrl: String
)

data class GuardDeviceSummaryResponse(
    val totalDevices: Int,
    val onlineDevices: Int,
    val offlineDevices: Int,
    val lockedDevices: Int,
    val pendingDevices: Int,
    val totalAlerts: Int
)
