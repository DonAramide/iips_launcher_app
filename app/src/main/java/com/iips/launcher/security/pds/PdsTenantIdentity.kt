package com.iips.launcher.security.pds

/**
 * Fail-closed tenant identity for PDS enrollment.
 *
 * Business names, agent/institutional codes, and the legacy sentinel
 * `"default"` are never acceptable PDS tenant identifiers.
 */
object PdsTenantIdentity {
    // Hex UUID shape (8-4-4-4-12). Stricter RFC variant rules remain for device IDs
    // via PdsEnrollmentIdentityPolicy; tenant IDs must never be business names.
    private val TENANT_UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )

    fun isValidTenantUuid(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val trimmed = value.trim()
        if (trimmed.equals("default", ignoreCase = true)) return false
        return TENANT_UUID_REGEX.matches(trimmed)
    }

    /**
     * @throws SecurityException when missing or not a UUID — callers must not
     * open a challenge request after this failure.
     */
    fun requireExplicitTenantUuid(explicitTenantId: String?): String {
        val trimmed = explicitTenantId?.trim().orEmpty()
        if (!isValidTenantUuid(trimmed)) {
            throw SecurityException(
                "PDS enrollment requires an explicit tenant UUID; " +
                    "refusing business-name or default tenant fallback"
            )
        }
        return trimmed
    }
}
