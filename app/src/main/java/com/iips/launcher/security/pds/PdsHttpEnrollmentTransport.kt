package com.iips.launcher.security.pds

import android.util.Log
import com.iips.launcher.BuildConfig
import com.iips.launcher.network.ConfigService
import com.iips.launcher.network.models.PdsEnrollmentChallengeRequest
import com.iips.launcher.network.models.PdsEnrollmentVerifyRequest
import com.iips.launcher.network.models.PdsEnrollmentVerifyResponse
import com.iips.launcher.network.models.PdsHardwareWire
import com.iips.launcher.network.models.QuasarApiEnvelope

/**
 * ConfigService-backed PDS enrollment transport.
 * Suspend-native. Consumes Quasar `{responseCode,responseMessage,data}` envelopes.
 * 409 without a recovery proof does not disclose or accept a device UUID.
 *
 * Recovery success is HTTP 409 with `data.error_code=enrollment_already_completed`
 * and identity fields under `data` (Retrofit `errorBody`). R-01: never use `body()`
 * for that path; read `errorBody()` once.
 */
class PdsHttpEnrollmentTransport(
    private val configService: ConfigService
) : PdsEnrollmentCoordinator.PdsEnrollmentTransport {

    companion object {
        private const val TAG = "PdsHttpEnrollment"

        /**
         * Debug-only WARN. Swallows JVM unit-test Log stub failures so diagnostics
         * never alter verify fail-closed behavior.
         */
        private fun debugWarnSanitized(message: String) {
            if (!BuildConfig.DEBUG) return
            try {
                Log.w(TAG, message)
            } catch (_: Throwable) {
                // android.util.Log is unavailable on plain JVM unit tests.
            }
        }
    }

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
        if (!response.isSuccessful) {
            throw SecurityException(
                "PDS enrollment challenge failed (HTTP ${response.code()})"
            )
        }
        val body = requireSuccessData(response.body(), "challenge")
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
        if (!response.isSuccessful) {
            // Sanitized diagnostics only: HTTP status + machine error_code.
            // Never log errorBody, tokens, signatures, or attestation chains.
            debugWarnSanitized(
                "PDS verify failed http=${response.code()} error_code=${errorCode ?: "absent"}"
            )
            throw PdsEnrollmentHttpException(response.code(), errorCode)
        }
        val body = requireSuccessData(response.body(), "verify")
        if (!PdsEnrollmentIdentityPolicy.isQuasarDeviceUuid(body.deviceId)) {
            throw SecurityException("PDS enrollment returned a non-UUID device identity")
        }
        return body.toResult()
    }

    private fun <T> requireSuccessData(envelope: QuasarApiEnvelope<T>?, label: String): T {
        if (envelope == null) {
            throw SecurityException("PDS $label response body missing")
        }
        val code = envelope.responseCode?.trim()
        if (!code.isNullOrEmpty() && code != "00") {
            throw SecurityException(
                "PDS $label rejected by Quasar responseCode=$code" +
                    (envelope.responseMessage?.let { " ($it)" } ?: "")
            )
        }
        return envelope.data
            ?: throw SecurityException("PDS $label response missing data envelope field")
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
