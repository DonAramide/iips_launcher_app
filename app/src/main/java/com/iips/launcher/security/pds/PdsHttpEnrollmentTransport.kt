package com.iips.launcher.security.pds

import com.iips.launcher.network.ConfigService
import com.iips.launcher.network.models.PdsEnrollmentChallengeRequest
import com.iips.launcher.network.models.PdsEnrollmentVerifyRequest
import com.iips.launcher.network.models.PdsEnrollmentVerifyResponse
import com.iips.launcher.network.models.PdsHardwareWire

/**
 * ConfigService-backed PDS enrollment transport.
 * Suspend-native. 409 without a recovery proof does not disclose or accept a device UUID.
 *
 * Quasar recovery success is HTTP 409 with top-level `error_code=enrollment_already_completed`
 * and identity fields in the **error** JSON (Retrofit `errorBody`). R-01: never use `body()`
 * for that path.
 */
class PdsHttpEnrollmentTransport(
    private val configService: ConfigService
) : PdsEnrollmentCoordinator.PdsEnrollmentTransport {

    override suspend fun requestChallenge(
        enrollmentToken: String,
        tenantId: String,
        devicePublicKeySpkiB64: String?,
        hardware: PdsEnrollmentCrypto.HardwareAttributes
    ): PdsEnrollmentCoordinator.ChallengeResponse {
        val response = configService.pdsEnrollmentChallenge(
            PdsEnrollmentChallengeRequest(
                enrollmentToken = enrollmentToken,
                tenantId = tenantId,
                devicePublicKeySpkiB64 = devicePublicKeySpkiB64,
                hardware = hardware.toWire()
            )
        )
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

    override suspend fun verifyEnrollment(
        challengeId: String,
        enrollmentToken: String,
        tenantId: String,
        devicePublicKeySpkiB64: String,
        hardware: PdsEnrollmentCrypto.HardwareAttributes,
        challengeSignatureB64Url: String,
        attestation: PdsAttestationCollector.CollectedEvidence?,
        recoveryNonce: String?,
        recoverySignatureB64Url: String?,
        claimedDeviceId: String?
    ): PdsEnrollmentCoordinator.EnrollmentResult {
        val recoverySent = !recoveryNonce.isNullOrBlank() && !recoverySignatureB64Url.isNullOrBlank()
        val response = configService.pdsEnrollmentVerify(
            PdsEnrollmentVerifyRequest(
                challengeId = challengeId,
                enrollmentToken = enrollmentToken,
                tenantId = tenantId,
                devicePublicKeySpkiB64 = devicePublicKeySpkiB64,
                hardware = hardware.toWire(),
                challengeSignatureB64Url = challengeSignatureB64Url,
                attestation = attestation?.toWireMap(),
                recoveryNonce = recoveryNonce,
                recoverySignatureB64Url = recoverySignatureB64Url,
                claimedDeviceId = claimedDeviceId
            )
        )
        // errorBody().string() may be read once — capture for code + recovery payload.
        val errorJson = response.errorBody()?.string()
        val errorCode = PdsEnrollmentHttpErrors.parseErrorCode(errorJson)

        if (response.code() == 409 && !recoverySent) {
            if (PdsEnrollmentHttpErrors.shouldEnterRecovery(response.code(), errorCode)) {
                throw PdsEnrollmentNeedsRecoveryException(challengeId)
            }
            throw PdsEnrollmentConflictException(errorCode ?: "http_409")
        }
        if (response.code() == 409 && recoverySent) {
            if (errorCode != PdsEnrollmentHttpErrors.ENROLLMENT_ALREADY_COMPLETED) {
                throw PdsEnrollmentConflictException(errorCode ?: "http_409")
            }
            val recovered = PdsEnrollmentHttpErrors.parseRecoveryEnrollmentResult(errorJson)
                ?: throw SecurityException(
                    "PDS enrollment recovery did not return a canonical device UUID (HTTP 409)"
                )
            return recovered.toResult()
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
        return body.toResult()
    }
}

private fun PdsEnrollmentVerifyResponse.toResult():
    PdsEnrollmentCoordinator.EnrollmentResult {
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
        enrolledAt = enrolledAt
    )
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
