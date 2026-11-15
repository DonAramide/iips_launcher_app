package com.iips.launcher.security.pds

class PdsEnrollmentStateIntegrityException(
    message: String
) : SecurityException(message)

class PdsServerIdentityMissingException(
    val deviceId: String
) : SecurityException(
    "Server reports device_not_registered for persisted identity $deviceId; authorized re-enrollment required"
)

class PdsAuthorizedRotationRequiredException(
    message: String
) : SecurityException(message)

class PdsEnrollmentConflictException(
    val errorCode: String
) : SecurityException("PDS enrollment conflict: $errorCode")

/**
 * Non-409 PDS enrollment HTTP failure with sanitized Quasar `error_code` retained
 * for diagnostics. Does not carry response bodies, tokens, or attestation material.
 */
class PdsEnrollmentHttpException(
    val httpStatus: Int,
    val errorCode: String?
) : SecurityException(formatMessage(httpStatus, errorCode)) {
    companion object {
        fun formatMessage(httpStatus: Int, errorCode: String?): String {
            val code = errorCode?.trim().orEmpty()
            return if (code.isEmpty()) {
                "PDS enrollment verification failed (HTTP $httpStatus)"
            } else {
                "PDS enrollment verification failed (HTTP $httpStatus, error_code=$code)"
            }
        }
    }
}
