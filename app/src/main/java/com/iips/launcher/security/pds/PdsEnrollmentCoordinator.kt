package com.iips.launcher.security.pds

/**
 * SPEC-03 challenge-response enrollment client.
 *
 * Orchestrates: collect hardware → request challenge → sign transcript →
 * verify enrollment → persist state. Transport is injected so unit tests do
 * not require a live HTTP stack.
 */
class PdsEnrollmentCoordinator(
    private val signer: PdsDeviceKeySigner,
    private val stateStore: PdsEnrollmentStateStore,
    private val transport: PdsEnrollmentTransport
) {
    data class ChallengeResponse(
        val challengeId: String,
        val challengeNonce: String,
        val attestationChallengeB64: String,
        val tenantId: String,
        val publicKeyFingerprintSha256: String,
        val hardwareFingerprintSha256: String,
        val issuedAt: String,
        val expiresAt: String
    )

    data class EnrollmentResult(
        val deviceId: String,
        val deviceIdentityRef: String,
        val tenantId: String,
        val deviceKeyId: String,
        val keyVersion: Long,
        val publicKeyFingerprintSha256: String,
        val lifecycleState: String,
        val attestationTrustTier: String,
        val allowedCapabilities: List<String>,
        val allowedCommands: List<String>,
        val enrolledAt: String
    )

    interface PdsEnrollmentTransport {
        fun requestChallenge(
            enrollmentToken: String,
            tenantId: String,
            devicePublicKeySpkiB64: String,
            hardware: PdsEnrollmentCrypto.HardwareAttributes
        ): ChallengeResponse

        fun verifyEnrollment(
            challengeId: String,
            enrollmentToken: String,
            tenantId: String,
            devicePublicKeySpkiB64: String,
            hardware: PdsEnrollmentCrypto.HardwareAttributes,
            challengeSignatureB64Url: String,
            attestation: PdsAttestationCollector.CollectedEvidence?
        ): EnrollmentResult
    }

    fun enroll(
        enrollmentToken: String,
        tenantId: String,
        hardware: PdsEnrollmentCrypto.HardwareAttributes,
        vendorAttestationToken: String? = null
    ): EnrollmentResult {
        if (enrollmentToken.isBlank() || tenantId.isBlank()) {
            throw IllegalArgumentException("enrollmentToken and tenantId are required")
        }
        if (hardware.serialNumber != null && PdsEnrollmentCrypto.isLikelyAndroidIdSerial(hardware.serialNumber)) {
            throw SecurityException("ANDROID_ID-shaped serial numbers are prohibited (SPEC-03 §1)")
        }
        if (signer.exportPrivateKeyBytesCatching() != null) {
            throw SecurityException("Device private key must be non-exportable")
        }

        val challenge = transport.requestChallenge(
            enrollmentToken = enrollmentToken,
            tenantId = tenantId,
            devicePublicKeySpkiB64 = signer.publicKeySpkiB64(),
            hardware = hardware
        )
        if (challenge.tenantId != tenantId) {
            throw SecurityException("Enrollment challenge tenant_id does not match requested tenant")
        }

        val hwFp = PdsEnrollmentCrypto.computeCanonicalHardwareFingerprint(hardware)
        val transcript = PdsEnrollmentCrypto.buildEnrollmentChallengeTranscript(
            PdsEnrollmentCrypto.EnrollmentTranscriptParams(
                challengeId = challenge.challengeId,
                challengeNonce = challenge.challengeNonce,
                enrollmentToken = enrollmentToken,
                tenantId = tenantId,
                publicKeyFingerprintSha256 = challenge.publicKeyFingerprintSha256,
                hardwareFingerprintSha256 = challenge.hardwareFingerprintSha256
            )
        )
        if (hwFp != challenge.hardwareFingerprintSha256) {
            throw SecurityException("Local hardware fingerprint does not match challenge binding")
        }
        val signature = signer.signPayload(transcript)
        val evidence = PdsAttestationCollector.classifyLocalCapabilities(
            isStrongBoxBacked = signer.isStrongBoxBacked(),
            isHardwareBacked = signer.isHardwareBacked(),
            attestationChainB64 = signer.attestationChainB64(),
            serverIssuedChallengeB64 = challenge.attestationChallengeB64,
            vendorAttestationToken = vendorAttestationToken
        )
        val enrolled = transport.verifyEnrollment(
            challengeId = challenge.challengeId,
            enrollmentToken = enrollmentToken,
            tenantId = tenantId,
            devicePublicKeySpkiB64 = signer.publicKeySpkiB64(),
            hardware = hardware,
            challengeSignatureB64Url = signature,
            attestation = evidence
        )
        stateStore.persistEnrollment(
            PdsEnrollmentStateStore.EnrollmentState(
                deviceId = enrolled.deviceId,
                deviceIdentityRef = enrolled.deviceIdentityRef,
                deviceKeyId = enrolled.deviceKeyId,
                tenantId = enrolled.tenantId,
                keyVersion = enrolled.keyVersion,
                publicKeyFingerprintSha256 = enrolled.publicKeyFingerprintSha256,
                lifecycleState = enrolled.lifecycleState,
                attestationTrustTier = enrolled.attestationTrustTier,
                allowedCapabilities = enrolled.allowedCapabilities,
                allowedCommands = enrolled.allowedCommands,
                enrolledAtIso = enrolled.enrolledAt,
                lastAppliedManifestVersion = 0L,
                lastAppliedManifestId = null,
                lastAppliedManifestJwsSha256 = null
            )
        )
        return enrolled
    }

    fun applyVerifiedManifest(
        verifier: PdsManifestVerifier,
        manifestJws: String,
        nowEpochSeconds: Long,
        expectedVendor: String? = null
    ): PdsManifestVerifier.CanonicalManifestClaims {
        val enrolled = stateStore.current()
            ?: throw SecurityException("Device is not enrolled")
        if (!enrolled.canReceiveProvisioning()) {
            throw SecurityException("Lifecycle ${enrolled.lifecycleState} cannot receive provisioning")
        }
        val outcome = verifier.verifyManifestJws(
            manifestJws,
            PdsManifestVerifier.VerificationOptions(
                expectedDeviceId = enrolled.deviceId,
                expectedDeviceIdentityRef = enrolled.deviceIdentityRef,
                expectedTenantId = enrolled.tenantId,
                expectedVendor = expectedVendor,
                lastAcceptedManifestVersion = enrolled.lastAppliedManifestVersion,
                nowEpochSeconds = nowEpochSeconds
            )
        )
        if (!outcome.valid || outcome.claims == null) {
            throw SecurityException("${outcome.failureCode}: ${outcome.failureReason}")
        }
        val jwsSha = PdsEnrollmentCrypto.sha256HexUtf8(manifestJws)
        stateStore.recordAppliedManifest(
            manifestId = outcome.claims.manifestId,
            manifestVersion = outcome.claims.manifestVersion,
            manifestJwsSha256 = jwsSha
        )
        return outcome.claims
    }
}

private fun PdsDeviceKeySigner.exportPrivateKeyBytesCatching(): ByteArray? {
    return try {
        exportPrivateKeyBytes()
    } catch (_: Exception) {
        null
    }
}
