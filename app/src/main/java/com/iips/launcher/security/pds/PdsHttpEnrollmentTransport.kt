package com.iips.launcher.security.pds

import com.iips.launcher.network.ConfigService
import com.iips.launcher.network.models.PdsEnrollmentChallengeRequest
import com.iips.launcher.network.models.PdsEnrollmentVerifyRequest
import com.iips.launcher.network.models.PdsHardwareWire
import kotlinx.coroutines.runBlocking

/**
 * ConfigService-backed PDS enrollment transport.
 * Coordinator remains testable via injected fakes; this adapter is fail-closed.
 */
class PdsHttpEnrollmentTransport(
    private val configService: ConfigService
) : PdsEnrollmentCoordinator.PdsEnrollmentTransport {

    override fun requestChallenge(
        enrollmentToken: String,
        tenantId: String,
        devicePublicKeySpkiB64: String,
        hardware: PdsEnrollmentCrypto.HardwareAttributes
    ): PdsEnrollmentCoordinator.ChallengeResponse {
        val response = runBlocking {
            configService.pdsEnrollmentChallenge(
                PdsEnrollmentChallengeRequest(
                    enrollmentToken = enrollmentToken,
                    tenantId = tenantId,
                    devicePublicKeySpkiB64 = devicePublicKeySpkiB64,
                    hardware = hardware.toWire()
                )
            )
        }
        if (!response.isSuccessful || response.body() == null) {
            throw SecurityException(
                "PDS enrollment challenge failed (HTTP ${response.code()})"
            )
        }
        val body = response.body()!!
        return PdsEnrollmentCoordinator.ChallengeResponse(
            challengeId = body.challengeId,
            challengeNonce = body.challengeNonce,
            attestationChallengeB64 = body.attestationChallengeB64,
            tenantId = body.tenantId,
            publicKeyFingerprintSha256 = body.publicKeyFingerprintSha256,
            hardwareFingerprintSha256 = body.hardwareFingerprintSha256,
            issuedAt = body.issuedAt,
            expiresAt = body.expiresAt
        )
    }

    override fun verifyEnrollment(
        challengeId: String,
        enrollmentToken: String,
        tenantId: String,
        devicePublicKeySpkiB64: String,
        hardware: PdsEnrollmentCrypto.HardwareAttributes,
        challengeSignatureB64Url: String,
        attestation: PdsAttestationCollector.CollectedEvidence?
    ): PdsEnrollmentCoordinator.EnrollmentResult {
        val response = runBlocking {
            configService.pdsEnrollmentVerify(
                PdsEnrollmentVerifyRequest(
                    challengeId = challengeId,
                    enrollmentToken = enrollmentToken,
                    tenantId = tenantId,
                    devicePublicKeySpkiB64 = devicePublicKeySpkiB64,
                    hardware = hardware.toWire(),
                    challengeSignatureB64Url = challengeSignatureB64Url,
                    attestation = attestation?.toWireMap()
                )
            )
        }
        if (!response.isSuccessful || response.body() == null) {
            throw SecurityException(
                "PDS enrollment verification failed (HTTP ${response.code()})"
            )
        }
        val body = response.body()!!
        if (!PdsEnrollmentIdentityPolicy.isQuasarDeviceUuid(body.deviceId)) {
            throw SecurityException("PDS enrollment returned a non-UUID device identity")
        }
        return PdsEnrollmentCoordinator.EnrollmentResult(
            deviceId = body.deviceId,
            deviceIdentityRef = body.deviceIdentityRef,
            tenantId = body.tenantId,
            deviceKeyId = body.deviceKeyId,
            keyVersion = body.keyVersion,
            publicKeyFingerprintSha256 = body.publicKeyFingerprintSha256,
            lifecycleState = body.lifecycleState,
            attestationTrustTier = body.attestationTrustTier,
            allowedCapabilities = body.allowedCapabilities,
            allowedCommands = body.allowedCommands,
            enrolledAt = body.enrolledAt
        )
    }
}

private fun PdsEnrollmentCrypto.HardwareAttributes.toWire(): PdsHardwareWire {
    return PdsHardwareWire(
        manufacturer = manufacturer,
        model = model,
        hardwareId = hardwareId,
        serialNumber = serialNumber,
        androidVersion = androidVersion,
        sdkInt = sdkInt,
        buildFingerprint = buildFingerprint,
        pdsVersion = pdsVersion,
        vendor = vendor,
        capabilityProfile = capabilityProfile
    )
}
