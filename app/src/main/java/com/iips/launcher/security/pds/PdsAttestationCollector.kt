package com.iips.launcher.security.pds

/**
 * SPEC-03 attestation capability layer.
 *
 * Never treats a self-reported manufacturer/model as hardware trust.
 * Unsupported terminals are represented explicitly as `unsupported`.
 */
object PdsAttestationCollector {
    enum class SecurityLevel {
        STRONGBOX,
        TEE,
        VENDOR_HARDWARE,
        SOFTWARE,
        UNSUPPORTED
    }

    enum class VerifiedBootState {
        VERIFIED,
        SELF_SIGNED,
        UNVERIFIED,
        UNKNOWN
    }

    enum class TrustTier {
        HARDWARE,
        HARDWARE_RELAXED,
        SOFTWARE,
        UNTRUSTED
    }

    data class CollectedEvidence(
        val securityLevel: SecurityLevel,
        val verifiedBootState: VerifiedBootState,
        val deviceLocked: Boolean?,
        val osPatchLevel: Long?,
        val attestationChallengeB64: String?,
        val certificateChainB64: List<String>,
        val vendorAttestationToken: String?,
        val isStrongBoxBacked: Boolean,
        val isHardwareBacked: Boolean
    ) {
        fun toWireMap(): Map<String, Any?> {
            return mapOf(
                "security_level" to securityLevel.name.lowercase(),
                "verified_boot_state" to verifiedBootState.name.lowercase(),
                "device_locked" to deviceLocked,
                "os_patch_level" to osPatchLevel,
                "attestation_challenge_b64" to attestationChallengeB64,
                "certificate_chain_b64" to certificateChainB64,
                "vendor_attestation_token" to vendorAttestationToken
            )
        }
    }

    /**
     * Classifies local keystore capabilities. Hardware trust is only a claim
     * until Quasar verifies the certificate chain / vendor token.
     */
    fun classifyLocalCapabilities(
        isStrongBoxBacked: Boolean,
        isHardwareBacked: Boolean,
        attestationChainB64: List<String>,
        serverIssuedChallengeB64: String?,
        vendorAttestationToken: String? = null,
        verifiedBootState: VerifiedBootState = VerifiedBootState.UNKNOWN,
        deviceLocked: Boolean? = null
    ): CollectedEvidence {
        val level = when {
            isStrongBoxBacked -> SecurityLevel.STRONGBOX
            isHardwareBacked && attestationChainB64.size > 1 -> SecurityLevel.TEE
            isHardwareBacked -> SecurityLevel.VENDOR_HARDWARE
            attestationChainB64.isNotEmpty() -> SecurityLevel.SOFTWARE
            else -> SecurityLevel.UNSUPPORTED
        }
        return CollectedEvidence(
            securityLevel = level,
            verifiedBootState = verifiedBootState,
            deviceLocked = deviceLocked,
            osPatchLevel = null,
            attestationChallengeB64 = serverIssuedChallengeB64,
            certificateChainB64 = attestationChainB64,
            vendorAttestationToken = vendorAttestationToken,
            isStrongBoxBacked = isStrongBoxBacked,
            isHardwareBacked = isHardwareBacked
        )
    }
}
