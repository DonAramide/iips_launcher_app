package com.iips.launcher.security.pds

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

/**
 * SPEC-03 enrollment transcripts and hardware fingerprints.
 *
 * Pure Kotlin so the same canonical bytes can be verified against Quasar
 * golden vectors without Android Keystore.
 */
object PdsEnrollmentCrypto {
    const val ENROLLMENT_TRANSCRIPT_PREFIX = "pds-enroll-v1"
    const val ENROLLMENT_RECOVERY_TRANSCRIPT_PREFIX = "pds-enroll-recover-v1"
    const val ENROLL_RECOVER_PURPOSE = "enroll_recover"
    const val KEY_ROTATION_TRANSCRIPT_PREFIX = "pds-key-rotate-v1"
    const val ATTESTATION_CHALLENGE_PREFIX = "pds-attest-v1"
    const val REQUEST_SIGNING_VERSION = "pds-req-v1"
    const val MIN_RECOVERY_NONCE_BYTES = 16

    data class HardwareAttributes(
        val manufacturer: String,
        val model: String,
        val hardwareId: String,
        val serialNumber: String?,
        val androidVersion: String,
        val sdkInt: Long,
        val buildFingerprint: String,
        val pdsVersion: String,
        val vendor: String? = null,
        val capabilityProfile: List<String> = emptyList()
    )

    fun sha256Bytes(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(input)
    }

    fun sha256Hex(input: ByteArray): String {
        return sha256Bytes(input).joinToString("") { "%02x".format(it) }
    }

    fun sha256HexUtf8(input: String): String {
        return sha256Hex(input.toByteArray(StandardCharsets.UTF_8))
    }

    fun publicKeyFingerprintSha256(spkiDer: ByteArray): String {
        return sha256Hex(spkiDer)
    }

    fun computeCanonicalHardwareFingerprint(hw: HardwareAttributes): String {
        val serial = hw.serialNumber?.trim()?.takeIf { it.isNotEmpty() } ?: "no_serial"
        val raw = listOf(
            hw.manufacturer.trim().lowercase(Locale.ROOT),
            hw.model.trim().lowercase(Locale.ROOT),
            hw.hardwareId.trim().lowercase(Locale.ROOT),
            serial,
            hw.buildFingerprint.trim()
        ).joinToString("|")
        return sha256HexUtf8(raw)
    }

    fun generateEnrollRecoverNonce(): String {
        val raw = ByteArray(MIN_RECOVERY_NONCE_BYTES)
        SecureRandom().nextBytes(raw)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    /**
     * v1.3 recovery PoP:
     * `pds-enroll-recover-v1|{challenge_id}|{device_id}|enroll_recover|{nonce}`
     * or without device_id when the first verify body was lost:
     * `pds-enroll-recover-v1|{challenge_id}|enroll_recover|{nonce}`
     */
    fun buildEnrollmentRecoveryTranscript(
        challengeId: String,
        nonce: String,
        deviceId: String? = null
    ): ByteArray {
        val parts = if (deviceId.isNullOrBlank()) {
            listOf(
                ENROLLMENT_RECOVERY_TRANSCRIPT_PREFIX,
                challengeId.trim(),
                ENROLL_RECOVER_PURPOSE,
                nonce.trim()
            )
        } else {
            listOf(
                ENROLLMENT_RECOVERY_TRANSCRIPT_PREFIX,
                challengeId.trim(),
                deviceId.trim(),
                ENROLL_RECOVER_PURPOSE,
                nonce.trim()
            )
        }
        return parts.joinToString("|").toByteArray(StandardCharsets.UTF_8)
    }

    fun decodeRequiredAttestationChallenge(attestationChallengeB64: String): ByteArray {
        val trimmed = attestationChallengeB64.trim()
        if (trimmed.isEmpty()) {
            throw SecurityException("Server attestation challenge is required")
        }
        val bytes = try {
            Base64.getDecoder().decode(trimmed)
        } catch (_: Exception) {
            throw SecurityException("Server attestation challenge is not valid Base64")
        }
        if (bytes.isEmpty()) {
            throw SecurityException("Server attestation challenge must be non-empty")
        }
        return bytes
    }

    fun buildEnrollmentChallengeTranscript(params: EnrollmentTranscriptParams): ByteArray {
        val tokenHashHex = sha256HexUtf8(params.enrollmentToken.trim())
        val canonical = listOf(
            ENROLLMENT_TRANSCRIPT_PREFIX,
            params.challengeId.trim(),
            params.challengeNonce.trim(),
            tokenHashHex,
            params.tenantId.trim(),
            params.publicKeyFingerprintSha256.trim().lowercase(Locale.ROOT),
            params.hardwareFingerprintSha256.trim().lowercase(Locale.ROOT)
        ).joinToString("|")
        return canonical.toByteArray(StandardCharsets.UTF_8)
    }

    fun buildKeyRotationTranscript(params: KeyRotationTranscriptParams): ByteArray {
        val canonical = listOf(
            KEY_ROTATION_TRANSCRIPT_PREFIX,
            params.deviceId.trim(),
            params.tenantId.trim(),
            params.currentDeviceKeyId.trim(),
            params.newPublicKeyFingerprintSha256.trim().lowercase(Locale.ROOT),
            params.timestampSec.toString(),
            params.nonce.trim()
        ).joinToString("|")
        return canonical.toByteArray(StandardCharsets.UTF_8)
    }

    fun buildDeviceRequestSigningBytes(params: DeviceRequestSigningParams): ByteArray {
        val bodyHashHex = sha256Hex(params.bodyBytes)
        val canonical = listOf(
            REQUEST_SIGNING_VERSION,
            params.method.trim().uppercase(Locale.ROOT),
            params.path.trim(),
            params.canonicalQuery.trim(),
            bodyHashHex,
            params.deviceId.trim(),
            params.keyId.trim(),
            params.timestampSec.toString(),
            params.nonce.trim()
        ).joinToString("\n")
        return canonical.toByteArray(StandardCharsets.UTF_8)
    }

    fun buildAttestationChallengeBytes(
        challengeId: String,
        challengeNonce: String,
        enrollmentToken: String
    ): ByteArray {
        val tokenHashHex = sha256HexUtf8(enrollmentToken.trim())
        val canonical = "$ATTESTATION_CHALLENGE_PREFIX|${challengeId.trim()}|${challengeNonce.trim()}|$tokenHashHex"
        return sha256Bytes(canonical.toByteArray(StandardCharsets.UTF_8))
    }

    fun isLikelyAndroidIdSerial(serial: String): Boolean {
        return Regex("^[0-9a-fA-F]{16}$").matches(serial.trim())
    }

    fun verifyDeviceSignature(
        publicKey: PublicKey,
        payloadBytes: ByteArray,
        signatureB64Url: String
    ): Boolean {
        val sigBytes = try {
            PdsCanonicalCommandEnvelope.fromBase64UrlStrict(signatureB64Url.trim(), "device.signature")
        } catch (_: Exception) {
            return false
        }
        return if (sigBytes.size == 64) {
            PdsCommandVerifier.verifyEs256Signature(publicKey, payloadBytes, sigBytes)
        } else {
            try {
                val verifier = java.security.Signature.getInstance("SHA256withECDSA")
                verifier.initVerify(publicKey)
                verifier.update(payloadBytes)
                verifier.verify(sigBytes)
            } catch (_: Exception) {
                false
            }
        }
    }

    data class EnrollmentTranscriptParams(
        val challengeId: String,
        val challengeNonce: String,
        val enrollmentToken: String,
        val tenantId: String,
        val publicKeyFingerprintSha256: String,
        val hardwareFingerprintSha256: String
    )

    data class KeyRotationTranscriptParams(
        val deviceId: String,
        val tenantId: String,
        val currentDeviceKeyId: String,
        val newPublicKeyFingerprintSha256: String,
        val timestampSec: Long,
        val nonce: String
    )

    data class DeviceRequestSigningParams(
        val method: String,
        val path: String,
        val canonicalQuery: String = "",
        val bodyBytes: ByteArray = ByteArray(0),
        val deviceId: String,
        val keyId: String,
        val timestampSec: Long,
        val nonce: String
    )
}

/**
 * Device-side signer abstraction. Production uses Android Keystore via
 * [PdsDeviceKeyManager]; unit tests use a software P-256 key that never
 * exposes the private key through this interface.
 */
interface PdsDeviceKeySigner {
    fun keyAlias(): String
    fun publicKeySpkiB64(): String
    fun publicKeyFingerprintSha256(): String
    fun signPayload(payloadBytes: ByteArray): String
    fun attestationChainB64(): List<String>
    fun isStrongBoxBacked(): Boolean
    fun isHardwareBacked(): Boolean
    fun inspectHardwareBacking(): PdsHardwareBackingClassifier.HardwareBackingInfo
    fun exportPrivateKeyBytes(): ByteArray
}

/**
 * Generates the device identity key **after** the server attestation challenge exists.
 * Production: Android Keystore P-256, non-exportable. No software private-key fallback.
 */
interface PdsDeviceKeyProvisioner {
    fun generateBoundToAttestationChallenge(attestationChallengeBytes: ByteArray)
    fun keyExists(): Boolean
}

class PdsEnrollmentNeedsRecoveryException(
    val challengeId: String
) : SecurityException("PDS enrollment already committed; recovery proof required")

class PdsIdentityConflictException(
    message: String
) : SecurityException(message)
