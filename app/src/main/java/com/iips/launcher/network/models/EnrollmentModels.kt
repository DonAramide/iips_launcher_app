package com.iips.launcher.network.models

import com.google.gson.annotations.SerializedName

/**
 * Enrollment request body for POST /api/v1/device/register.
 *
 * Sent automatically by [com.iips.launcher.provisioning.ProvisioningBootstrapService]
 * immediately after Android Enterprise QR provisioning completes.
 *
 * NOTE: enrollment_token must NEVER be logged.
 */
data class EnrollmentRequest(
    @SerializedName("enrollment_token")  val enrollmentToken: String,
    @SerializedName("manufacturer")      val manufacturer: String,
    @SerializedName("model")             val model: String,
    @SerializedName("serial_number")     val serialNumber: String?,
    @SerializedName("fingerprint_hash")  val fingerprintHash: String,
    @SerializedName("agent_code")        val agentCode: String? = null,
    @SerializedName("business_name")     val businessName: String? = null,
    @SerializedName("latitude")          val latitude: Double? = null,
    @SerializedName("longitude")         val longitude: Double? = null,
    @SerializedName("location")          val location: LocationInfo? = null
)

/**
 * Successful response from POST /api/v1/device/register.
 */
data class EnrollmentResponse(
    @SerializedName("responseCode")    val responseCode: String?,
    @SerializedName("responseMessage") val responseMessage: String?,
    @SerializedName("data")            val data: EnrollmentData?,
    @SerializedName("device")          val rootDevice: EnrollmentDevice? = null,
    @SerializedName("access_token")    val rootAccessToken: String? = null,
    @SerializedName("expires_in")      val rootExpiresIn: Long? = null,
    @SerializedName("token_type")      val rootTokenType: String? = null
) {
    val deviceId: String
        get() = (data?.device?.id ?: rootDevice?.id) ?: ""

    val accessToken: String?
        get() = data?.accessToken ?: rootAccessToken

    val deviceSecret: String?
        get() = null

    val expiresIn: Long
        get() = data?.expiresIn ?: rootExpiresIn ?: 0L

    val tokenType: String
        get() = data?.tokenType ?: rootTokenType ?: ""

    val status: String?
        get() = data?.device?.status ?: rootDevice?.status
}

data class EnrollmentData(
    @SerializedName("device")       val device: EnrollmentDevice?,
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("expires_in")   val expiresIn: Long?,
    @SerializedName("token_type")   val tokenType: String?
)

data class EnrollmentDevice(
    @SerializedName("id")               val id: String,
    @SerializedName("device_uid")       val deviceUid: String?,
    @SerializedName("status")           val status: String?
)


/**
 * Wrapper for enrollment error body returned by Quasar.
 */
data class EnrollmentErrorResponse(
    @SerializedName("error")   val error: String?,
    @SerializedName("message") val message: String?,
    @SerializedName("code")    val code: String?
)
