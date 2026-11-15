package com.iips.launcher.security.pds

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SPEC-03 / v1.3 challenge-response enrollment client.
 *
 * Order: resume pending challenge or request a new one (no Keystore key) →
 * persist pending metadata → generate Keystore key with those attestation
 * bytes → sign enroll transcript → verify. Recovery signs
 * `pds-enroll-recover-v1` only in recovery fields.
 */
class PdsEnrollmentCoordinator(
    private val signer: PdsDeviceKeySigner,
    private val provisioner: PdsDeviceKeyProvisioner,
    private val stateStore: PdsEnrollmentStateStore,
    private val transport: PdsEnrollmentTransport,
    private val identityStatus: PdsDeviceIdentityStatusClient,
    private val pendingStore: PdsPendingEnrollmentStore,
    private val nowEpochMilli: () -> Long = { System.currentTimeMillis() },
    /**
     * App-private directory for Phase 6.5.4.3A non-PEM forensic capture.
     * Null disables persistence even when the debug capture gate is on.
     */
    private val attestationForensicDir: java.io.File? = null,
) {
    private val enrollMutex = Mutex()

    data class ChallengeResponse(
        val challengeId: String,
        val challengeNonce: String,
        val attestationChallengeB64: String,
        val tenantId: String,
        val publicKeyFingerprintSha256: String?,
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
        suspend fun requestChallenge(
            enrollmentToken: String,
            tenantId: String,
            devicePublicKeySpkiB64: String?,
            hardware: PdsEnrollmentCrypto.HardwareAttributes
        ): ChallengeResponse

        suspend fun verifyEnrollment(
            challengeId: String,
            enrollmentToken: String,
            tenantId: String,
            devicePublicKeySpkiB64: String,
            hardware: PdsEnrollmentCrypto.HardwareAttributes,
            challengeSignatureB64Url: String,
            attestation: PdsAttestationCollector.CollectedEvidence?,
            recoveryNonce: String? = null,
            recoverySignatureB64Url: String? = null,
            claimedDeviceId: String? = null
        ): EnrollmentResult
    }

    suspend fun enroll(
        enrollmentToken: String,
        tenantId: String,
        hardware: PdsEnrollmentCrypto.HardwareAttributes,
        vendorAttestationToken: String? = null
    ): EnrollmentResult {
        return enrollMutex.withLock {
            enrollExclusive(enrollmentToken, tenantId, hardware, vendorAttestationToken)
        }
    }

    private suspend fun enrollExclusive(
        enrollmentToken: String,
        tenantId: String,
        hardware: PdsEnrollmentCrypto.HardwareAttributes,
        vendorAttestationToken: String?
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

        val existing = stateStore.current()
        if (existing != null && existing.pdsEnrollmentComplete && existing.tenantId == tenantId) {
            return reusePersistedIdentity(existing, enrollmentToken, tenantId)
        }

        val challenge = resolveChallenge(enrollmentToken, tenantId, hardware)
        if (challenge.tenantId != tenantId) {
            throw SecurityException("Enrollment challenge tenant_id does not match requested tenant")
        }

        val attestationBytes = PdsEnrollmentCrypto.decodeRequiredAttestationChallenge(
            challenge.attestationChallengeB64
        )
        provisioner.generateBoundToAttestationChallenge(attestationBytes)
        if (signer.exportPrivateKeyBytesCatching() != null) {
            throw SecurityException("Device private key must be non-exportable")
        }

        val localPubFp = signer.publicKeyFingerprintSha256()
        val hwFp = PdsEnrollmentCrypto.computeCanonicalHardwareFingerprint(hardware)
        if (hwFp != challenge.hardwareFingerprintSha256) {
            throw SecurityException("Local hardware fingerprint does not match challenge binding")
        }
        if (!challenge.publicKeyFingerprintSha256.isNullOrBlank() &&
            challenge.publicKeyFingerprintSha256.lowercase() != localPubFp.lowercase()
        ) {
            throw SecurityException("Challenge public-key fingerprint does not match Keystore key")
        }

        val transcript = PdsEnrollmentCrypto.buildEnrollmentChallengeTranscript(
            PdsEnrollmentCrypto.EnrollmentTranscriptParams(
                challengeId = challenge.challengeId,
                challengeNonce = challenge.challengeNonce,
                enrollmentToken = enrollmentToken,
                tenantId = tenantId,
                publicKeyFingerprintSha256 = localPubFp,
                hardwareFingerprintSha256 = challenge.hardwareFingerprintSha256
            )
        )
        val enrollSignature = pendingStore.current()?.enrollSignatureB64Url
            ?: signer.signPayload(transcript)
        pendingStore.recordEnrollSignature(enrollSignature)

        val attestationChainB64 = signer.attestationChainB64()
        captureAttestationForensics(
            chainB64 = attestationChainB64,
            hardware = hardware,
            attestationChallengeConfigured = true,
        )

        val evidence = PdsAttestationCollector.classifyLocalCapabilities(
            isStrongBoxBacked = signer.isStrongBoxBacked(),
            isHardwareBacked = signer.isHardwareBacked(),
            attestationChainB64 = attestationChainB64,
            serverIssuedChallengeB64 = challenge.attestationChallengeB64,
            vendorAttestationToken = vendorAttestationToken
        )
        val enrolled = try {
            transport.verifyEnrollment(
                challengeId = challenge.challengeId,
                enrollmentToken = enrollmentToken,
                tenantId = tenantId,
                devicePublicKeySpkiB64 = signer.publicKeySpkiB64(),
                hardware = hardware,
                challengeSignatureB64Url = enrollSignature,
                attestation = evidence
            )
        } catch (_: PdsEnrollmentNeedsRecoveryException) {
            completeRecovery(
                challenge = challenge,
                enrollmentToken = enrollmentToken,
                tenantId = tenantId,
                hardware = hardware,
                evidence = evidence,
                enrollSignature = enrollSignature
            )
        }
        if (!PdsEnrollmentIdentityPolicy.isQuasarDeviceUuid(enrolled.deviceId)) {
            throw SecurityException("PDS enrollment refused a non-UUID cryptographic identity")
        }
        val claimed = existing?.deviceId
        if (!claimed.isNullOrBlank() && claimed != enrolled.deviceId) {
            throw PdsIdentityConflictException(
                "Server identity does not match persisted canonical UUID; refusing replacement"
            )
        }
        persistPdsOnly(enrolled, challenge.challengeId)
        pendingStore.clear()
        return enrolled
    }

    private suspend fun reusePersistedIdentity(
        existing: PdsEnrollmentStateStore.EnrollmentState,
        enrollmentToken: String,
        tenantId: String
    ): EnrollmentResult {
        if (!PdsEnrollmentIdentityPolicy.isQuasarDeviceUuid(existing.deviceId)) {
            throw SecurityException("Persisted PDS identity is not a canonical UUID")
        }
        val probe = identityStatus.probeRegisteredIdentity(
            deviceId = existing.deviceId,
            tenantId = tenantId,
            enrollmentToken = enrollmentToken
        )
        return when (probe.kind) {
            PdsDeviceIdentityStatusKind.CONFIRMED_PRESENT -> existing.toEnrollmentResult()
            PdsDeviceIdentityStatusKind.INDETERMINATE -> existing.toEnrollmentResult()
            PdsDeviceIdentityStatusKind.DEFINITIVELY_MISSING ->
                throw PdsServerIdentityMissingException(existing.deviceId)
        }
    }

    private suspend fun resolveChallenge(
        enrollmentToken: String,
        tenantId: String,
        hardware: PdsEnrollmentCrypto.HardwareAttributes
    ): ChallengeResponse {
        val pending = pendingStore.current()
        val now = nowEpochMilli()
        if (pending != null && pending.tenantId == tenantId) {
            if (!pending.isExpired(now)) {
                return pending.toChallenge()
            }
            if (provisioner.keyExists()) {
                throw PdsAuthorizedRotationRequiredException(
                    "Pending enrollment challenge ${pending.challengeId} expired while a Keystore key exists; " +
                        "authorized key replacement is required. The alias was not deleted."
                )
            }
            pendingStore.clear()
        }
        val challenge = transport.requestChallenge(
            enrollmentToken = enrollmentToken,
            tenantId = tenantId,
            devicePublicKeySpkiB64 = null,
            hardware = hardware
        )
        val attestationBytes = PdsEnrollmentCrypto.decodeRequiredAttestationChallenge(
            challenge.attestationChallengeB64
        )
        pendingStore.persist(
            PdsPendingEnrollmentStore.PendingEnrollment(
                challengeId = challenge.challengeId,
                challengeNonce = challenge.challengeNonce,
                attestationChallengeB64 = challenge.attestationChallengeB64,
                attestationChallengeHashSha256 = PdsEnrollmentCrypto.sha256Hex(attestationBytes),
                tenantId = challenge.tenantId,
                publicKeyFingerprintSha256 = challenge.publicKeyFingerprintSha256,
                hardwareFingerprintSha256 = challenge.hardwareFingerprintSha256,
                issuedAt = challenge.issuedAt,
                expiresAt = challenge.expiresAt
            )
        )
        return challenge
    }

    private suspend fun completeRecovery(
        challenge: ChallengeResponse,
        enrollmentToken: String,
        tenantId: String,
        hardware: PdsEnrollmentCrypto.HardwareAttributes,
        evidence: PdsAttestationCollector.CollectedEvidence?,
        enrollSignature: String
    ): EnrollmentResult {
        val claimed = stateStore.current()?.deviceId?.takeIf { PdsEnrollmentIdentityPolicy.isQuasarDeviceUuid(it) }
        val nonce = PdsEnrollmentCrypto.generateEnrollRecoverNonce()
        if (nonce.length < 16) {
            throw SecurityException("Recovery nonce too short")
        }
        val recoveryTranscript = PdsEnrollmentCrypto.buildEnrollmentRecoveryTranscript(
            challengeId = challenge.challengeId,
            nonce = nonce,
            deviceId = claimed
        )
        val recoverySig = signer.signPayload(recoveryTranscript)
        val recovered = transport.verifyEnrollment(
            challengeId = challenge.challengeId,
            enrollmentToken = enrollmentToken,
            tenantId = tenantId,
            devicePublicKeySpkiB64 = signer.publicKeySpkiB64(),
            hardware = hardware,
            challengeSignatureB64Url = enrollSignature,
            attestation = evidence,
            recoveryNonce = nonce,
            recoverySignatureB64Url = recoverySig,
            claimedDeviceId = claimed
        )
        if (!PdsEnrollmentIdentityPolicy.isQuasarDeviceUuid(recovered.deviceId)) {
            throw SecurityException("Recovery returned a non-UUID device identity")
        }
        if (!claimed.isNullOrBlank() && claimed != recovered.deviceId) {
            throw PdsIdentityConflictException(
                "Recovery device_id does not match persisted canonical UUID"
            )
        }
        return recovered
    }

    /**
     * Phase 6.5.4.3A: sanitized attestation forensic capture immediately after
     * Keystore chain retrieval and before Quasar verify transport. Soft-fails.
     */
    private fun captureAttestationForensics(
        chainB64: List<String>,
        hardware: PdsEnrollmentCrypto.HardwareAttributes,
        attestationChallengeConfigured: Boolean,
    ) {
        val dir = attestationForensicDir ?: return
        try {
            val device = PdsAttestationForensicCapture.deviceProfileFromBuild(
                manufacturer = hardware.manufacturer,
                model = hardware.model,
            )
            val keystore = PdsAttestationForensicCapture.keystoreEvidenceFromSigner(
                signer = signer,
                chainLength = chainB64.size,
                attestationChallengeConfigured = attestationChallengeConfigured,
            )
            PdsAttestationForensicCapture.captureBeforeQuasarTransmit(
                outputDir = dir,
                chainB64 = chainB64,
                device = device,
                keystore = keystore,
                nowMillis = nowEpochMilli(),
            )
        } catch (_: Throwable) {
            // Never alter enrollment decision.
        }
    }

    private fun persistPdsOnly(enrolled: EnrollmentResult, challengeId: String) {
        val previous = stateStore.current()
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
                lastAppliedManifestVersion = previous?.lastAppliedManifestVersion ?: 0L,
                lastAppliedManifestId = previous?.lastAppliedManifestId,
                lastAppliedManifestJwsSha256 = previous?.lastAppliedManifestJwsSha256,
                pdsEnrollmentComplete = true,
                mdmRegistrationComplete = previous?.mdmRegistrationComplete ?: false,
                lastChallengeId = challengeId
            )
        )
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

private fun PdsEnrollmentStateStore.EnrollmentState.toEnrollmentResult(): PdsEnrollmentCoordinator.EnrollmentResult {
    return PdsEnrollmentCoordinator.EnrollmentResult(
        deviceId = deviceId,
        deviceIdentityRef = deviceIdentityRef,
        tenantId = tenantId,
        deviceKeyId = deviceKeyId,
        keyVersion = keyVersion,
        publicKeyFingerprintSha256 = publicKeyFingerprintSha256,
        lifecycleState = lifecycleState,
        attestationTrustTier = attestationTrustTier,
        allowedCapabilities = allowedCapabilities,
        allowedCommands = allowedCommands,
        enrolledAt = enrolledAtIso
    )
}

private fun PdsDeviceKeySigner.exportPrivateKeyBytesCatching(): ByteArray? {
    return try {
        exportPrivateKeyBytes()
    } catch (_: Exception) {
        null
    }
}
