package com.iips.launcher.security.pds

/**
 * Deliverable 8 — Device-bound command authorization layer.
 *
 * Cryptographic validity of a Quasar signature does NOT automatically authorize execution.
 * This layer enforces:
 * 1. Device enrollment status (`isEnrolled` and lifecycle state in `{ACTIVE, LOCKED}`).
 * 2. Device assignment (`assignedDeviceId` and optional `assignedDeviceIdentityRef`).
 * 3. Tenant scope (`assignedTenantId == claims.tenantId`).
 * 4. Command type allowlist (`ALLOWED_PDS_COMMAND_TYPES`).
 * 5. Device capability (e.g. Device Owner / Device Admin required for privileged OS actions).
 * 6. Administrative permission & command-specific approval requirements (`approval_ref`).
 */
class PdsCommandAuthorizer {
    enum class AuthorizationDenialCode {
        DEVICE_NOT_ENROLLED,
        DEVICE_ASSIGNMENT_MISMATCH,
        TENANT_SCOPE_MISMATCH,
        TYPE_NOT_ALLOWED,
        CAPABILITY_MISSING,
        APPROVAL_REQUIRED,
        PROHIBITED_PAYLOAD
    }

    data class DeviceAuthorizationContext(
        val isEnrolled: Boolean,
        val lifecycleState: String,
        val assignedDeviceId: String?,
        val assignedDeviceIdentityRef: String? = null,
        val assignedTenantId: String?,
        val isDeviceOwner: Boolean,
        val isDeviceAdminActive: Boolean,
        val allowedCommandTypes: Set<String> = DEFAULT_ALLOWED_COMMAND_TYPES
    )

    data class AuthorizationOutcome(
        val authorized: Boolean,
        val denialCode: AuthorizationDenialCode? = null,
        val denialReason: String? = null,
        val canonicalCommandType: String? = null
    )

    companion object {
        val DEFAULT_ALLOWED_COMMAND_TYPES: Set<String> = setOf(
            "REBOOT",
            "RELAUNCH",
            "OTA_TRIGGER",
            "KIOSK_REFRESH",
            "ENFORCE_KIOSK",
            "SET_KIOSK_MODE",
            "POLICY_SYNC",
            "UPDATE_REQUEST",
            "APP_REFRESH",
            "LIST_APPS",
            "REMOTE_DIAGNOSTICS",
            "LOCK",
            "UNLOCK",
            "RING",
            "SHUTDOWN",
            "FACTORY_RESET",
            "DISABLE_SETTINGS",
            "RESTRICT_APP_USAGE",
            "PUSH_FILE",
            "UPDATE_APP_DATA",
            "INSTALL_APK",
            "INSTALL",
            "UNINSTALL_APP",
            "UNINSTALL",
            "PUSH_NOTIFICATION",
            "CRITICAL_ALERT",
            "PAYMENT_NOTIFICATION",
            "GENERAL",
            "LOCATION_REQUEST",
            "PING",
            "SET_SHARED_PARAMS",
            "PARAMETER_REQUEST",
            "ENTERPRISE_ROLLOUT"
        )

        val DEVICE_OWNER_REQUIRED_COMMANDS: Set<String> = setOf(
            "REBOOT",
            "FACTORY_RESET",
            "DISABLE_SETTINGS",
            "RESTRICT_APP_USAGE",
            "INSTALL_APK",
            "INSTALL",
            "UNINSTALL_APP",
            "UNINSTALL",
            "ENTERPRISE_ROLLOUT"
        )

        val DEVICE_ADMIN_REQUIRED_COMMANDS: Set<String> = setOf(
            "LOCK"
        )

        val SENSITIVE_APPROVAL_COMMANDS: Set<String> = setOf(
            "FACTORY_RESET",
            "INSTALL_APK",
            "INSTALL",
            "UNINSTALL_APP",
            "UNINSTALL"
        )
    }

    fun authorize(
        claims: PdsCanonicalCommandEnvelope.CanonicalCommandClaims,
        deviceContext: DeviceAuthorizationContext
    ): AuthorizationOutcome {
        // 1. Device enrollment status
        val state = deviceContext.lifecycleState.trim().uppercase()
        if (!deviceContext.isEnrolled || state !in setOf("ACTIVE", "LOCKED", "REGISTERED")) {
            return AuthorizationOutcome(
                authorized = false,
                denialCode = AuthorizationDenialCode.DEVICE_NOT_ENROLLED,
                denialReason = "Device is not in an enrolled/active state (state=$state)"
            )
        }

        // 2. Device assignment
        val assignedId = deviceContext.assignedDeviceId?.trim()
        if (assignedId.isNullOrEmpty() || assignedId != claims.deviceId) {
            return AuthorizationOutcome(
                authorized = false,
                denialCode = AuthorizationDenialCode.DEVICE_ASSIGNMENT_MISMATCH,
                denialReason = "Command device_id (${claims.deviceId}) does not match assigned device ($assignedId)"
            )
        }

        val assignedIdentityRef = deviceContext.assignedDeviceIdentityRef?.trim()
        if (!assignedIdentityRef.isNullOrEmpty() && assignedIdentityRef != claims.deviceIdentityRef) {
            return AuthorizationOutcome(
                authorized = false,
                denialCode = AuthorizationDenialCode.DEVICE_ASSIGNMENT_MISMATCH,
                denialReason = "Command device_identity_ref does not match assigned device identity"
            )
        }

        // 3. Tenant scope
        val assignedTenant = deviceContext.assignedTenantId?.trim()
        if (assignedTenant.isNullOrEmpty() || assignedTenant != claims.tenantId) {
            return AuthorizationOutcome(
                authorized = false,
                denialCode = AuthorizationDenialCode.TENANT_SCOPE_MISMATCH,
                denialReason = "Command tenant_id (${claims.tenantId}) does not match device tenant ($assignedTenant)"
            )
        }

        // 4. Command type allowlist
        val normalizedType = claims.type.trim().uppercase()
        if (!deviceContext.allowedCommandTypes.contains(normalizedType)) {
            return AuthorizationOutcome(
                authorized = false,
                denialCode = AuthorizationDenialCode.TYPE_NOT_ALLOWED,
                denialReason = "Command type $normalizedType is not in the PDS allowlist"
            )
        }

        // 5. Prohibited payload content (SPEC-04 §3: block decrypt_keys in SET_SHARED_PARAMS)
        try {
            PdsCanonicalCommandEnvelope.assertNoProhibitedPayloadContent(normalizedType, claims.payload)
        } catch (e: Exception) {
            return AuthorizationOutcome(
                authorized = false,
                denialCode = AuthorizationDenialCode.PROHIBITED_PAYLOAD,
                denialReason = e.message ?: "Prohibited command payload content"
            )
        }

        // 6. Administrative approval requirement for sensitive commands
        if (SENSITIVE_APPROVAL_COMMANDS.contains(normalizedType) && claims.approvalRef.isNullOrBlank()) {
            return AuthorizationOutcome(
                authorized = false,
                denialCode = AuthorizationDenialCode.APPROVAL_REQUIRED,
                denialReason = "Sensitive command $normalizedType requires maker-checker approval_ref"
            )
        }

        // 7. Device capability check
        if (DEVICE_OWNER_REQUIRED_COMMANDS.contains(normalizedType) && !deviceContext.isDeviceOwner) {
            return AuthorizationOutcome(
                authorized = false,
                denialCode = AuthorizationDenialCode.CAPABILITY_MISSING,
                denialReason = "Command $normalizedType requires Device Owner capability"
            )
        }

        if (
            DEVICE_ADMIN_REQUIRED_COMMANDS.contains(normalizedType) &&
            !deviceContext.isDeviceOwner &&
            !deviceContext.isDeviceAdminActive
        ) {
            return AuthorizationOutcome(
                authorized = false,
                denialCode = AuthorizationDenialCode.CAPABILITY_MISSING,
                denialReason = "Command $normalizedType requires Device Admin or Device Owner capability"
            )
        }

        return AuthorizationOutcome(
            authorized = true,
            canonicalCommandType = normalizedType
        )
    }
}
