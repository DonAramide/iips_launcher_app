package com.iips.launcher.security.pds

/**
 * Single mandatory fail-closed command gate combining:
 * 1. Asymmetric ES256 signature + envelope verification (`PdsCommandVerifier`)
 * 2. Device-bound authorization (`PdsCommandAuthorizer`)
 * 3. Persistent atomic replay reservation (`PersistentCommandReplayStore`)
 *
 * A command is reserved in the persistent replay store ONLY after both cryptographic verification
 * and authorization succeed, and BEFORE any command handler executes.
 */
class PdsCommandGate(
    val verifier: PdsCommandVerifier,
    val authorizer: PdsCommandAuthorizer,
    val replayStore: PersistentCommandReplayStore
) {
    data class GateDecision(
        val allowed: Boolean,
        val errorCode: String? = null,
        val errorMessage: String? = null,
        val claims: PdsCanonicalCommandEnvelope.CanonicalCommandClaims? = null,
        val signatureB64: String? = null
    )

    fun verifyAuthorizeAndReserve(
        commandJws: String?,
        deviceContext: PdsCommandAuthorizer.DeviceAuthorizationContext,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
        maxClockSkewSeconds: Long = 60L
    ): GateDecision {
        // Step 1: Cryptographic & envelope verification
        val verifyRes = verifier.verifyCommandJws(
            commandJws = commandJws,
            options = PdsCommandVerifier.VerificationOptions(
                expectedDeviceId = deviceContext.assignedDeviceId,
                expectedDeviceIdentityRef = deviceContext.assignedDeviceIdentityRef,
                nowEpochSeconds = nowEpochSeconds,
                maxClockSkewSeconds = maxClockSkewSeconds
            )
        )
        if (!verifyRes.valid || verifyRes.claims == null) {
            return GateDecision(
                allowed = false,
                errorCode = verifyRes.failureCode?.name ?: "SIG_INVALID",
                errorMessage = verifyRes.failureReason ?: "Command signature verification failed"
            )
        }

        val claims = verifyRes.claims

        // Step 2: Authorization check (enrollment, assignment, tenant, type, capability, approval)
        val authRes = authorizer.authorize(claims, deviceContext)
        if (!authRes.authorized) {
            return GateDecision(
                allowed = false,
                errorCode = authRes.denialCode?.name ?: "UNAUTHORIZED",
                errorMessage = authRes.denialReason ?: "Command not authorized for this device",
                claims = claims
            )
        }

        // Step 3: Persistent atomic replay check & reservation BEFORE execution
        val expiresAtSec = PdsCanonicalCommandEnvelope.parseUtcIsoSeconds(claims.expiresAt, "expires_at")
        val replayDecision = replayStore.checkAndReserveAtomically(
            commandId = claims.commandId,
            nonce = claims.nonce,
            deviceId = claims.deviceId,
            expiresAtEpochSec = expiresAtSec,
            nowEpochSec = nowEpochSeconds
        )
        if (replayDecision != PersistentCommandReplayStore.ReplayDecision.ACCEPTED) {
            return GateDecision(
                allowed = false,
                errorCode = replayDecision.name,
                errorMessage = "Replay protection rejected command (${replayDecision.name})",
                claims = claims
            )
        }

        return GateDecision(
            allowed = true,
            claims = claims,
            signatureB64 = verifyRes.signatureB64
        )
    }
}
