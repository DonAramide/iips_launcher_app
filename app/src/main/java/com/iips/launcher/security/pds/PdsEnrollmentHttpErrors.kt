package com.iips.launcher.security.pds

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.iips.launcher.network.models.PdsEnrollmentVerifyResponse

/**
 * Classifies PDS enrollment HTTP errors. Only
 * `enrollment_already_completed` may enter the recovery path.
 *
 * Quasar recovery success places identity fields in the same 409 JSON as
 * `error_code` (Retrofit `errorBody`), not in `body()`.
 */
object PdsEnrollmentHttpErrors {
    const val ENROLLMENT_ALREADY_COMPLETED = "enrollment_already_completed"
    const val DEVICE_NOT_REGISTERED = "device_not_registered"
    const val IDENTITY_MISMATCH = "identity_mismatch"
    const val DEVICE_OWNED_BY_OTHER_TENANT = "device_owned_by_other_tenant"
    const val DUPLICATE_DEVICE_ENROLLMENT = "duplicate_device_enrollment"
    const val ENROLLMENT_CHALLENGE_REPLAYED = "enrollment_challenge_replayed"

    private val gson = Gson()

    fun parseErrorCode(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            sequenceOf("error_code", "error", "code")
                .mapNotNull { key ->
                    if (!obj.has(key) || obj.get(key).isJsonNull) {
                        null
                    } else {
                        obj.get(key).asString.trim().takeIf { it.isNotEmpty() }
                    }
                }
                .firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun shouldEnterRecovery(httpCode: Int, errorCode: String?): Boolean {
        return httpCode == 409 && errorCode == ENROLLMENT_ALREADY_COMPLETED
    }

    /**
     * Strict recovery payload from 409 errorBody. Requires exact
     * `enrollment_already_completed` and a canonical device UUID plus required fields.
     */
    fun parseRecoveryEnrollmentResult(errorJson: String?): PdsEnrollmentVerifyResponse? {
        if (errorJson.isNullOrBlank()) return null
        if (parseErrorCode(errorJson) != ENROLLMENT_ALREADY_COMPLETED) return null
        return try {
            val parsed = gson.fromJson(errorJson, PdsEnrollmentVerifyResponse::class.java)
                ?: return null
            if (!PdsEnrollmentIdentityPolicy.isQuasarDeviceUuid(parsed.deviceId)) return null
            if (parsed.deviceIdentityRef.isBlank() ||
                parsed.tenantId.isBlank() ||
                parsed.deviceKeyId.isBlank() ||
                parsed.publicKeyFingerprintSha256.isBlank() ||
                parsed.lifecycleState.isBlank()
            ) {
                return null
            }
            parsed
        } catch (_: Exception) {
            null
        }
    }
}
