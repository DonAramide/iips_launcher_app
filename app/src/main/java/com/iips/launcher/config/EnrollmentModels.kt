package com.iips.launcher.config

import com.google.gson.annotations.SerializedName

/**
 * Enrollment request body for POST /api/v1/devices/enroll.
 *
 * Sent automatically by [com.iips.launcher.device.ProvisioningBootstrapService]
 * immediately after Android Enterprise QR provisioning completes.
 *
 * NOTE: enrollment_token must NEVER be logged.
 */
data class EnrollmentRequest(
    @SerializedName("enrollment_token")  val enrollmentToken: String,
    @SerializedName("tenant_id")         val tenantId: String?,
    @SerializedName("policy_group_id")   val policyGroupId: String?,
    @SerializedName("manufacturer")      val manufacturer: String,
    @SerializedName("model")             val model: String,
    @SerializedName("android_version")   val androidVersion: String,
    @SerializedName("serial_number")     val serialNumber: String?,
    /** Build.FINGERPRINT — raw platform fingerprint string. */
    @SerializedName("fingerprint")       val fingerprint: String,
    /** SHA-256 hex of "manufacturer|model|serial" per Quasar §4. */
    @SerializedName("fingerprint_hash")  val fingerprintHash: String,
    @SerializedName("app_version")       val appVersion: String
)

/**
 * Successful response from POST /api/v1/devices/enroll.
 *
 * [deviceSecret] is stored encrypted and never logged.
 */
data class EnrollmentResponse(
    @SerializedName("device_id")     val deviceId: String,
    @SerializedName("device_secret") val deviceSecret: String?,
    @SerializedName("access_token")  val accessToken: String,
    @SerializedName("expires_in")    val expiresIn: Long,
    @SerializedName("token_type")    val tokenType: String,
    /** Optional: server-assigned status after enrollment (e.g. "ACTIVE"). */
    @SerializedName("status")        val status: String? = null
)

/**
 * Wrapper for enrollment error body returned by Quasar.
 */
data class EnrollmentErrorResponse(
    @SerializedName("error")   val error: String?,
    @SerializedName("message") val message: String?,
    @SerializedName("code")    val code: String?
)
