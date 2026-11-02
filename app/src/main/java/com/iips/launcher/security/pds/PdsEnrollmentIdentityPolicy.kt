package com.iips.launcher.security.pds

/**
 * Enrollment identity rules (Phase 1C.2 C7).
 *
 * Cryptographic identity is the Quasar-issued device UUID. Serial numbers
 * and ANDROID_ID-shaped values are never used as the persisted device id.
 */
object PdsEnrollmentIdentityPolicy {
    private val UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
    )

    fun isQuasarDeviceUuid(id: String): Boolean {
        return UUID_REGEX.matches(id.trim())
    }

    fun isLegacyCompatibilityIdentity(storedId: String): Boolean {
        val trimmed = storedId.trim()
        if (trimmed.isEmpty()) return false
        if (isQuasarDeviceUuid(trimmed)) return false
        return true
    }

    fun selectPersistedDeviceId(pdsDeviceId: String, legacyRegisterDeviceId: String?): String {
        val pds = pdsDeviceId.trim()
        require(isQuasarDeviceUuid(pds)) {
            "PDS enrollment must return a Quasar device UUID, not a serial number"
        }
        if (!legacyRegisterDeviceId.isNullOrBlank() &&
            isQuasarDeviceUuid(legacyRegisterDeviceId) &&
            legacyRegisterDeviceId.trim() != pds
        ) {
            return pds
        }
        return pds
    }
}
