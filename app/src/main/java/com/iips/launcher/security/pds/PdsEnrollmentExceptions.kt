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
