package com.iips.launcher.security.pds

/**
 * Authenticated identity-status probe. Quasar 1D.2 must implement the server
 * endpoint. Network/auth failures are indeterminate and must not mint a
 * replacement identity.
 */
enum class PdsDeviceIdentityStatusKind {
    CONFIRMED_PRESENT,
    DEFINITIVELY_MISSING,
    INDETERMINATE
}

data class PdsDeviceIdentityProbe(
    val kind: PdsDeviceIdentityStatusKind,
    val errorCode: String? = null
)

interface PdsDeviceIdentityStatusClient {
    suspend fun probeRegisteredIdentity(
        deviceId: String,
        tenantId: String,
        enrollmentToken: String
    ): PdsDeviceIdentityProbe
}
