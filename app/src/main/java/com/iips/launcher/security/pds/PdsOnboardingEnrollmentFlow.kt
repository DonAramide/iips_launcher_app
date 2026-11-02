package com.iips.launcher.security.pds

/**
 * Isolated onboarding sequence used by DeviceEnrollmentManager.
 * Executable with fakes: no production devices, no live payments.
 */
object PdsOnboardingEnrollmentFlow {
    data class LegacyRegisterRequest(
        val enrollmentToken: String,
        val manufacturer: String,
        val model: String,
        val serialNumber: String?,
        val fingerprintHash: String,
        val agentCode: String?,
        val businessName: String?,
        val knownDeviceId: String
    )

    data class LegacyRegisterResult(
        val success: Boolean,
        val httpCode: Int,
        val deviceId: String?,
        val accessToken: String?
    )

    data class Outcome(
        val persistedDeviceId: String,
        val accessToken: String,
        val pdsDeviceId: String,
        val knownDeviceIdSentToLegacy: String
    )

    fun execute(
        tenantId: String?,
        enrollmentToken: String,
        manufacturer: String,
        model: String,
        serial: String?,
        fingerprintHash: String,
        agentCode: String?,
        businessName: String?,
        pdsEnroll: (token: String, tenant: String, hardware: PdsEnrollmentCrypto.HardwareAttributes) -> PdsEnrollmentCoordinator.EnrollmentResult,
        hardware: PdsEnrollmentCrypto.HardwareAttributes,
        legacyRegister: (LegacyRegisterRequest) -> LegacyRegisterResult
    ): Outcome {
        if (tenantId.isNullOrBlank()) {
            throw SecurityException("PDS enrollment requires tenant_id; refusing serial-number identity fallback")
        }
        if (!serial.isNullOrBlank() && PdsEnrollmentCrypto.isLikelyAndroidIdSerial(serial)) {
            throw SecurityException("ANDROID_ID-shaped serial numbers are prohibited (SPEC-03 §1)")
        }
        val enrolled = pdsEnroll(enrollmentToken, tenantId, hardware)
        if (!PdsEnrollmentIdentityPolicy.isQuasarDeviceUuid(enrolled.deviceId)) {
            throw SecurityException("PDS enrollment refused a non-UUID cryptographic identity")
        }
        val legacy = legacyRegister(
            LegacyRegisterRequest(
                enrollmentToken = enrollmentToken,
                manufacturer = manufacturer,
                model = model,
                serialNumber = serial,
                fingerprintHash = fingerprintHash,
                agentCode = agentCode,
                businessName = businessName,
                knownDeviceId = enrolled.deviceId
            )
        )
        if (!legacy.success) {
            throw SecurityException("Legacy MDM token exchange failed (HTTP ${legacy.httpCode})")
        }
        val persisted = PdsEnrollmentIdentityPolicy.selectPersistedDeviceId(
            enrolled.deviceId,
            legacy.deviceId
        )
        val token = legacy.accessToken
        if (token.isNullOrBlank()) {
            throw SecurityException("Legacy MDM token exchange returned no access token")
        }
        return Outcome(
            persistedDeviceId = persisted,
            accessToken = token,
            pdsDeviceId = enrolled.deviceId,
            knownDeviceIdSentToLegacy = enrolled.deviceId
        )
    }
}
