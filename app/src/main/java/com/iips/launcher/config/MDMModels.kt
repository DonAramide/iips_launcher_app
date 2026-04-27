package com.iips.launcher.config

import com.google.gson.annotations.SerializedName

/**
 * Registration request sent on first launch.
 */
data class RegistrationRequest(
    @SerializedName("serial_number") val serialNumber: String,
    @SerializedName("device_model") val deviceModel: String,
    @SerializedName("manufacturer") val manufacturer: String,
    @SerializedName("android_version") val androidVersion: String,
    @SerializedName("app_version") val appVersion: String
)

/**
 * Response from the registration endpoint.
 */
data class RegistrationResponse(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_token") val deviceToken: String
)

/**
 * Heartbeat (Telemetry) request sent periodically.
 */
data class HeartbeatRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("timestamp") val timestamp: String, // ISO8601
    @SerializedName("location") val location: LocationInfo,
    @SerializedName("battery") val battery: BatteryInfo,
    @SerializedName("uptime_seconds") val uptimeSeconds: Long,
    @SerializedName("network") val network: NetworkInfo,
    @SerializedName("sim") val sim: SimInfo,
    @SerializedName("device_state") val deviceState: DeviceStateInfo,
    @SerializedName("printer") val printer: PrinterInfo?
)

data class LocationInfo(
    @SerializedName("lat") val lat: Double,
    @SerializedName("lng") val lng: Double,
    @SerializedName("accuracy") val accuracy: Float
)

data class BatteryInfo(
    @SerializedName("level") val level: Int,
    @SerializedName("charging") val charging: Boolean
)

data class NetworkInfo(
    @SerializedName("type") val type: String, // WIFI, MOBILE, NONE
    @SerializedName("is_connected") val isConnected: Boolean
)

data class SimInfo(
    @SerializedName("is_present") val isPresent: Boolean,
    @SerializedName("carrier") val carrier: String?,
    @SerializedName("phone_number") val phoneNumber: String? = null
)

data class DeviceStateInfo(
    @SerializedName("screen_on") val screenOn: Boolean,
    @SerializedName("is_locked") val isLocked: Boolean
)

data class PrinterInfo(
    @SerializedName("connected") val connected: Boolean,
    @SerializedName("name") val name: String?,
    @SerializedName("battery") val battery: Int?
)

/**
 * Geofencing configuration response from the server.
 */
data class GeofenceConfigResponse(
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("zones") val zones: List<GeofenceZone>?
)

data class GeofenceZone(
    @SerializedName("name") val name: String,
    @SerializedName("lat") val lat: Double,
    @SerializedName("lng") val lng: Double,
    @SerializedName("radius") val radius: Float,
    @SerializedName("status") val status: String? = "pending" // "pending" | "approved"
)

/**
 * Geofence proposal request from device.
 */
data class GeofenceProposalRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("zones") val zones: List<GeofenceZone>
)

/**
 * Geofence status update request.
 */
data class GeofenceStatusRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("lat") val lat: Double,
    @SerializedName("lng") val lng: Double,
    @SerializedName("inside_zone") val insideZone: Boolean,
    @SerializedName("mode") val mode: String, // "enrollment" | "enforcement"
    @SerializedName("battery") val battery: Int,
    @SerializedName("mock_detected") val mockDetected: Boolean,
    @SerializedName("timestamp") val timestamp: String // ISO8601
)
