package com.iips.launcher.network.models

import com.google.gson.annotations.SerializedName

/**
 * Registration request sent on first launch or recovery (§3.1).
 */
data class RegistrationRequest(
    @SerializedName("enrollment_token") val enrollment_token: String,
    @SerializedName("manufacturer") val manufacturer: String,
    @SerializedName("model") val model: String,
    @SerializedName("serial_number") val serial_number: String?,
    @SerializedName("fingerprint_hash") val fingerprint_hash: String
)

/**
 * Response from the registration endpoint (§3.1).
 */
data class RegistrationResponse(
    @SerializedName("device") val device: MdmDevice,
    @SerializedName("access_token") val access_token: String,
    @SerializedName("expires_in") val expires_in: Long,
    @SerializedName("token_type") val token_type: String
)

data class MdmDevice(
    @SerializedName("id") val id: String,
    @SerializedName("device_uid") val device_uid: String,
    @SerializedName("serial_number") val serial_number: String?,
    @SerializedName("manufacturer") val manufacturer: String,
    @SerializedName("model") val model: String,
    @SerializedName("fingerprint_hash") val fingerprint_hash: String,
    @SerializedName("enrollment_token") val enrollment_token: String,
    @SerializedName("status") val status: String,
    @SerializedName("last_seen_at") val last_seen_at: String?,
    @SerializedName("enrolled_at") val enrolled_at: String?,
    @SerializedName("wiped_at") val wiped_at: String?,
    @SerializedName("created_at") val created_at: String,
    @SerializedName("updated_at") val updated_at: String
)

/**
 * Heartbeat (Telemetry) request sent periodically (§3.3).
 */
data class HeartbeatRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("tenant_id") val tenantId: String,
    @SerializedName("telemetry_seq") val telemetrySeq: Long,
    @SerializedName("battery_level") val batteryLevel: Int,
    @SerializedName("is_charging") val isCharging: Boolean,
    @SerializedName("network_status") val networkStatus: String,
    @SerializedName("uptime") val uptime: Long, // seconds
    @SerializedName("location") val location: LocationInfo? = null,
    @SerializedName("device_time") val deviceTime: Long,
    @SerializedName("is_sim_present") val isSimPresent: Boolean,
    @SerializedName("sim_operator") val simOperator: String,
    @SerializedName("sim_network_type") val simNetworkType: String,
    @SerializedName("sim_details") val simDetails: List<com.iips.launcher.core.SimDetail>,
    @SerializedName("serial_number") val serialNumber: String? = null
)

data class LocationInfo(
    @SerializedName("lat") val lat: Double,
    @SerializedName("lng") val lng: Double
)

/**
 * Client-side event request (§3.4).
 */
data class MdmEventRequest(
    @SerializedName("type") val type: String,
    @SerializedName("payload") val payload: Map<String, Any>
)

/**
 * MDM command received via WebSocket or Polling.
 */
data class MdmCommand(
    @SerializedName("id") val id: String, // UUID
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("type") val type: String, // LOCK, REBOOT, INSTALL_APK, etc.
    @SerializedName("payload") val payload: String? = null, // JSON string
    @SerializedName("timestamp") val timestamp: Long, // Unix seconds
    @SerializedName("nonce") val nonce: String,
    @SerializedName("expires_at") val expiresAt: Long, // Unix seconds
    @SerializedName("signature") val signature: String
)

/**
 * MDM command acknowledgement.
 */
data class CommandAcknowledgement(
    @SerializedName("command_id") val commandId: String,
    @SerializedName("status") val status: String, // "RECEIVED", "VALIDATED", "EXECUTING", "SUCCESS", "FAILED"
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis() / 1000,
    @SerializedName("message") val message: String? = null,
    @SerializedName("error_code") val errorCode: String? = null
)

/**
 * Policy Response as per stub (§3.2).
 */
data class PolicyResponse(
    @SerializedName("allowed_apps") val allowed_apps: List<String>?,
    @SerializedName("blocked_apps") val blocked_apps: List<String>?,
    @SerializedName("kiosk_mode") val kiosk_mode: Boolean,
    @SerializedName("geofence_rules") val geofence_rules: List<GeofenceRule>?,
    @SerializedName("settings_lock") val settings_lock: Boolean,
    @SerializedName("install_queue") val install_queue: List<InstallQueueItem>?
)

data class PolicyEnvelope(
    @SerializedName("responseCode") val responseCode: String?,
    @SerializedName("responseMessage") val responseMessage: String?,
    @SerializedName("data") val data: PolicyResponse?
)

data class GeofenceRule(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("lat") val lat: Double,
    @SerializedName("lng") val lng: Double,
    @SerializedName("radius_m") val radius_m: Double
)

data class InstallQueueItem(
    @SerializedName("package_name") val package_name: String,
    @SerializedName("version") val version: String?
)

data class BatteryInfo(
    val level: Int,
    val charging: Boolean
)

data class NetworkInfo(
    val type: String,
    val isConnected: Boolean
)

data class DevicePolicySnapshot(
    val version: String,
    val maxPolicyAge: Long,
    val lastUpdatedAt: Long,
    
    // Application Policies
    val allowedApps: List<String> = emptyList(),
    val blockedApps: List<String> = emptyList(),
    val requiredApps: List<String> = emptyList(),
    val forbiddenApps: List<String> = emptyList(),
    
    // Kiosk & UI Policies
    val kioskMode: Boolean = false,
    val lockTaskMode: Boolean = false,
    val statusBarDisabled: Boolean = false,
    val settingsLock: Boolean = false,
    val screenCaptureDisabled: Boolean = false,
    val kioskPinEnabled: Boolean = false,
    val kioskPin: String? = null,
    
    // System Restrictions
    val safeBootDisabled: Boolean = true,
    val factoryResetDisabled: Boolean = true,
    val usbFileTransferDisabled: Boolean = true,
    val adbDisabled: Boolean = true,
    val developerOptionsDisabled: Boolean = true,
    val installUnknownAppsDisabled: Boolean = true,
    val cameraDisabled: Boolean = false,
    val microphoneDisabled: Boolean = false,
    
    // Network & Connectivity
    val vpnRestricted: Boolean = false,
    val wifiRestricted: Boolean = false,
    val bluetoothRestricted: Boolean = false,
    
    // Security & Compliance
    val passwordRequirements: PasswordRequirements? = null,
    val accessibilityRestrictions: List<String> = emptyList(),
    
    // Dynamic Rules
    val geofenceRules: List<GeofenceRule> = emptyList(),
    val installQueue: List<InstallQueueItem> = emptyList()
)

data class PasswordRequirements(
    val minLength: Int = 0,
    val quality: Int = 0, // DevicePolicyManager.PASSWORD_QUALITY_*
    val maxFailedAttempts: Int = 0
)

data class InstallResultRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("app_id") val appId: String,
    @SerializedName("version") val version: String,
    @SerializedName("status") val status: String, // SUCCESS | FAILED
    @SerializedName("error_message") val errorMessage: String? = null
)
