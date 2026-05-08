package com.iips.launcher.config

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
    @SerializedName("battery_level") val battery_level: Int,
    @SerializedName("is_charging") val is_charging: Boolean,
    @SerializedName("network_status") val network_status: String,
    @SerializedName("uptime") val uptime: Long, // seconds
    @SerializedName("location") val location: LocationInfo? = null
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
    @SerializedName("allowed_apps") val allowed_apps: List<String>,
    @SerializedName("blocked_apps") val blocked_apps: List<String>,
    @SerializedName("kiosk_mode") val kiosk_mode: Boolean,
    @SerializedName("geofence_rules") val geofence_rules: List<GeofenceRule>,
    @SerializedName("settings_lock") val settings_lock: Boolean,
    @SerializedName("install_queue") val install_queue: List<InstallQueueItem>
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

/**
 * Legacy/Internal structures below (if still needed for local mapping)
 */

data class KioskConfig(
    @SerializedName("kiosk_enabled") val kioskEnabled: Boolean,
    @SerializedName("lock_task_mode") val lockTaskMode: Boolean,
    @SerializedName("system_restrictions") val systemRestrictions: Boolean
)

data class InstallResultRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("app_id") val appId: String,
    @SerializedName("version") val version: String,
    @SerializedName("status") val status: String, // SUCCESS | FAILED
    @SerializedName("error_message") val errorMessage: String? = null
)

