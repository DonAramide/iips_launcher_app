package com.iips.launcher.security.pds

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class PdsCommandVerifier(
    pinnedRootSpkiB64: Map<String, String> = DEFAULT_PINNED_ROOT_KEYS,
    initialKeys: List<TrustedCommandPublicKey> = DEFAULT_BOOTSTRAP_COMMAND_KEYS
) {
    enum class KeyPurpose { COMMAND, MANIFEST, ROOT }
    enum class KeyStatus { ACTIVE, NEXT, RETIRING, REVOKED }

    enum class VerificationFailureCode {
        MALFORMED_ENVELOPE,
        UNSUPPORTED_ALG,
        KID_MISSING,
        KID_UNKNOWN,
        KID_REVOKED,
        KID_OUTSIDE_VALIDITY,
        SIG_INVALID,
        WRONG_DEVICE,
        EXPIRED,
        FUTURE_ISSUED,
        APPROVAL_REQUIRED
    }

    data class TrustedCommandPublicKey(
        val kid: String,
        val purpose: KeyPurpose,
        val status: KeyStatus,
        val notBeforeIso: String,
        val notAfterIso: String,
        val publicKeySpkiB64: String
    )

    data class VerificationOptions(
        val expectedDeviceId: String?,
        val expectedDeviceIdentityRef: String? = null,
        val nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
        val maxClockSkewSeconds: Long = 60L
    )

    data class VerificationOutcome(
        val valid: Boolean,
        val failureCode: VerificationFailureCode? = null,
        val failureReason: String? = null,
        val claims: PdsCanonicalCommandEnvelope.CanonicalCommandClaims? = null,
        val signatureB64: String? = null,
        val keyRecord: TrustedCommandPublicKey? = null
    )

    companion object {
        private val P256_ORDER_N = BigInteger(
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
            16
        )

        val DEFAULT_PINNED_ROOT_KEYS: Map<String, String> = mapOf(
            "root-2026-v1" to "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAExCTtYPAwt+CNMKHp8rpeuPpAzGC1CdN89qsr7VA5VRxggiXKxb8PatRzikOgUpXBi3wSivHqH+h53YfAEN8jcg=="
        )

        val DEFAULT_BOOTSTRAP_COMMAND_KEYS: List<TrustedCommandPublicKey> = listOf(
            TrustedCommandPublicKey(
                kid = "cmd-2026-11-active",
                purpose = KeyPurpose.COMMAND,
                status = KeyStatus.ACTIVE,
                notBeforeIso = "2026-10-01T00:00:00Z",
                notAfterIso = "2027-11-01T00:00:00Z",
                publicKeySpkiB64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE80JwlpEbDWjvAAtK2xrIJDvFyQGxj1EDjrN+Gy2F2gss6J911iU9M9fe/MXhPYeIu4DPDF0dkahIqWD8m83tng=="
            ),
            TrustedCommandPublicKey(
                kid = "cmd-2026-12-next",
                purpose = KeyPurpose.COMMAND,
                status = KeyStatus.NEXT,
                notBeforeIso = "2026-11-01T00:00:00Z",
                notAfterIso = "2027-12-01T00:00:00Z",
                publicKeySpkiB64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEJX71JmJyqYn7vqfCRE+qvMTiwdxc+TJHbluWvENiX2BBeOflEHyYa27BUXlCs5Gsl+jZacb6V9MB5oMFI2fl8w=="
            ),
            TrustedCommandPublicKey(
                kid = "cmd-2026-05-retiring",
                purpose = KeyPurpose.COMMAND,
                status = KeyStatus.RETIRING,
                notBeforeIso = "2026-05-01T00:00:00Z",
                notAfterIso = "2026-12-31T23:59:59Z",
                publicKeySpkiB64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEd/joX48VOgamD4+Pu+1Zj0VdRTRkaVXwdMnlxenwSYDFJowm4o0UDYIArAvNMfd8xgPInANMZU8KdZfEE5M0Zg=="
            ),
            TrustedCommandPublicKey(
                kid = "cmd-2026-01-revoked",
                purpose = KeyPurpose.COMMAND,
                status = KeyStatus.REVOKED,
                notBeforeIso = "2026-01-01T00:00:00Z",
                notAfterIso = "2026-06-01T00:00:00Z",
                publicKeySpkiB64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEWJ2HK3ZVjepcGFiEXrOQIR1gfFuMaQbzbYGHKObr51iEtOMQtEWGtib8q427FF4RtJN0NvP0WuvGLbtG9Izzdg=="
            ),
            TrustedCommandPublicKey(
                kid = "mfs-2026-11-active",
                purpose = KeyPurpose.MANIFEST,
                status = KeyStatus.ACTIVE,
                notBeforeIso = "2026-10-01T00:00:00Z",
                notAfterIso = "2027-11-01T00:00:00Z",
                publicKeySpkiB64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAETQ0fnOa1jVQLmseuWn89alS0a50LnHEOsYHfM5mys+TL80ZyeY11ZPIZbumnQ9fsp7dpbnEn2xiAmpNPhJNCDA=="
            )
        )

        fun decodeEcPublicKey(spkiB64: String): PublicKey {
            val der = Base64.getDecoder().decode(spkiB64.trim())
            val spec = X509EncodedKeySpec(der)
            return KeyFactory.getInstance("EC").generatePublic(spec)
        }

        /**
         * Converts a 64-byte IEEE P1363 (r || s) ECDSA P-256 signature into ASN.1 DER
         * after validating `1 <= r < N` and `1 <= s < N`.
         */
        fun p1363ToDer(sigBytes: ByteArray): ByteArray {
            if (sigBytes.size != 64) {
                throw IllegalArgumentException("ES256 P1363 signature must be 64 bytes")
            }
            val r = BigInteger(1, sigBytes.copyOfRange(0, 32))
            val s = BigInteger(1, sigBytes.copyOfRange(32, 64))
            if (r < BigInteger.ONE || r >= P256_ORDER_N || s < BigInteger.ONE || s >= P256_ORDER_N) {
                throw IllegalArgumentException("ES256 signature r or s scalar out of P-256 order bounds")
            }
            val rDer = r.toByteArray()
            val sDer = s.toByteArray()
            val seqLen = 2 + rDer.size + 2 + sDer.size
            val out = ByteArrayOutputStream(2 + seqLen)
            out.write(0x30)
            if (seqLen >= 128) {
                out.write(0x81)
            }
            out.write(seqLen)
            out.write(0x02)
            out.write(rDer.size)
            out.write(rDer)
            out.write(0x02)
            out.write(sDer.size)
            out.write(sDer)
            return out.toByteArray()
        }

        fun verifyEs256Signature(
            publicKey: PublicKey,
            signingInputBytes: ByteArray,
            p1363Signature: ByteArray
        ): Boolean {
            return try {
                val derSig = p1363ToDer(p1363Signature)
                val verifier = Signature.getInstance("SHA256withECDSA")
                verifier.initVerify(publicKey)
                verifier.update(signingInputBytes)
                verifier.verify(derSig)
            } catch (_: Exception) {
                false
            }
        }
    }

    private val rootKeys = ConcurrentHashMap<String, PublicKey>()
    private val keyRegistry = ConcurrentHashMap<String, Pair<TrustedCommandPublicKey, PublicKey>>()
    @Volatile
    private var acceptedKeySetVersion: Long = 1L

    init {
        for ((kid, spki) in pinnedRootSpkiB64) {
            rootKeys[kid] = decodeEcPublicKey(spki)
        }
        for (k in initialKeys) {
            keyRegistry[k.kid] = k to decodeEcPublicKey(k.publicKeySpkiB64)
        }
    }

    fun getAcceptedKeySetVersion(): Long = acceptedKeySetVersion

    /**
     * Verifies and applies a root-signed key-set JWS (`SPEC-01` §4).
     * Enforces anti-rollback (`version >= acceptedKeySetVersion`).
     */
    @Synchronized
    fun applySignedKeySetJws(keysetJws: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): Long {
        val parts = keysetJws.trim().split(".")
        if (parts.size != 3) {
            throw SecurityException("Malformed keyset_jws")
        }
        val headerBytes = PdsCanonicalCommandEnvelope.fromBase64UrlStrict(parts[0], "keyset.header")
        val payloadBytes = PdsCanonicalCommandEnvelope.fromBase64UrlStrict(parts[1], "keyset.payload")
        val sigBytes = PdsCanonicalCommandEnvelope.fromBase64UrlStrict(parts[2], "keyset.signature")

        @Suppress("UNCHECKED_CAST")
        val header = PdsCanonicalCommandEnvelope.parseJsonRejectingDuplicateKeys(
            String(headerBytes, StandardCharsets.UTF_8)
        ) as? Map<String, Any?> ?: throw SecurityException("Invalid keyset header")

        if (header["alg"] != "ES256" || header["typ"] != PdsCanonicalCommandEnvelope.KEYSET_JWS_TYP || header["v"] != 1L) {
            throw SecurityException("Unsupported keyset JWS header")
        }
        val rootKid = header["kid"] as? String ?: throw SecurityException("Missing root kid")
        val rootPub = rootKeys[rootKid] ?: throw SecurityException("Untrusted root key: $rootKid")

        val signingInput = "${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII)
        if (!verifyEs256Signature(rootPub, signingInput, sigBytes)) {
            throw SecurityException("Keyset root signature verification failed")
        }

        @Suppress("UNCHECKED_CAST")
        val payload = PdsCanonicalCommandEnvelope.parseJsonRejectingDuplicateKeys(
            String(payloadBytes, StandardCharsets.UTF_8)
        ) as? Map<String, Any?> ?: throw SecurityException("Invalid keyset payload")

        val version = payload["version"] as? Long ?: throw SecurityException("Missing keyset version")
        if (version < acceptedKeySetVersion) {
            throw SecurityException("Keyset version rollback rejected: $version < $acceptedKeySetVersion")
        }

        val expiresIso = payload["expires_at"] as? String ?: throw SecurityException("Missing keyset expires_at")
        val expiresSec = PdsCanonicalCommandEnvelope.parseUtcIsoSeconds(expiresIso, "keyset.expires_at")
        if (nowEpochSeconds > expiresSec) {
            throw SecurityException("Keyset distribution has expired")
        }

        @Suppress("UNCHECKED_CAST")
        val rawKeys = payload["keys"] as? List<Map<String, Any?>>
            ?: throw SecurityException("Missing keys list in keyset")

        val updatedMap = HashMap<String, Pair<TrustedCommandPublicKey, PublicKey>>()
        for (kObj in rawKeys) {
            val kid = kObj["kid"] as? String ?: throw SecurityException("Missing kid in key entry")
            val purposeStr = (kObj["purpose"] as? String)?.uppercase() ?: throw SecurityException("Missing purpose")
            val statusStr = (kObj["status"] as? String)?.uppercase() ?: throw SecurityException("Missing status")
            val notBefore = kObj["not_before"] as? String ?: throw SecurityException("Missing not_before")
            val notAfter = kObj["not_after"] as? String ?: throw SecurityException("Missing not_after")
            val spkiB64 = kObj["public_key_spki_b64"] as? String ?: throw SecurityException("Missing public_key_spki_b64")
            val purpose = KeyPurpose.valueOf(purposeStr)
            val status = KeyStatus.valueOf(statusStr)
            val rec = TrustedCommandPublicKey(
                kid = kid,
                purpose = purpose,
                status = status,
                notBeforeIso = notBefore,
                notAfterIso = notAfter,
                publicKeySpkiB64 = spkiB64
            )
            updatedMap[kid] = rec to decodeEcPublicKey(spkiB64)
        }

        keyRegistry.clear()
        keyRegistry.putAll(updatedMap)
        acceptedKeySetVersion = version
        return version
    }

    /**
     * Verifies a compact ES256 command JWS fail-closed.
     */
    fun verifyCommandJws(commandJws: String?, options: VerificationOptions): VerificationOutcome {
        if (commandJws.isNullOrBlank()) {
            return VerificationOutcome(
                valid = false,
                failureCode = VerificationFailureCode.MALFORMED_ENVELOPE,
                failureReason = "Missing command_jws"
            )
        }

        val parsed = try {
            PdsCanonicalCommandEnvelope.parseAndValidateCompactCommandJws(commandJws)
        } catch (e: Exception) {
            val msg = e.message ?: "Invalid command JWS"
            val code = when {
                msg.contains("Unsupported JWS header alg") || msg.contains("Unsupported command signature algorithm") ->
                    VerificationFailureCode.UNSUPPORTED_ALG
                msg.contains("Missing JWS header kid") ||
                    msg.contains("Invalid JWS header kid") ||
                    msg.contains("Invalid command signing key_id") ||
                    msg.contains("field \"key_id\"") ->
                    VerificationFailureCode.KID_MISSING
                else -> VerificationFailureCode.MALFORMED_ENVELOPE
            }
            return VerificationOutcome(valid = false, failureCode = code, failureReason = msg)
        }

        val claims = parsed.claims
        val entry = keyRegistry[claims.keyId]
        if (entry == null || entry.first.purpose != KeyPurpose.COMMAND) {
            return VerificationOutcome(
                valid = false,
                failureCode = VerificationFailureCode.KID_UNKNOWN,
                failureReason = "Unknown command signing key: ${claims.keyId}"
            )
        }

        val (keyRecord, publicKey) = entry
        if (keyRecord.status == KeyStatus.REVOKED) {
            return VerificationOutcome(
                valid = false,
                failureCode = VerificationFailureCode.KID_REVOKED,
                failureReason = "Signing key ${claims.keyId} has been revoked"
            )
        }

        if (!verifyEs256Signature(publicKey, parsed.signingInputBytes, parsed.signatureBytes)) {
            return VerificationOutcome(
                valid = false,
                failureCode = VerificationFailureCode.SIG_INVALID,
                failureReason = "ES256 cryptographic signature verification failed"
            )
        }

        val expectedDev = options.expectedDeviceId?.trim()
        if (expectedDev.isNullOrEmpty() || claims.deviceId != expectedDev) {
            return VerificationOutcome(
                valid = false,
                failureCode = VerificationFailureCode.WRONG_DEVICE,
                failureReason = "Command device_id \"${claims.deviceId}\" does not match device \"$expectedDev\""
            )
        }

        val expectedRef = options.expectedDeviceIdentityRef?.trim()
        if (!expectedRef.isNullOrEmpty() && claims.deviceIdentityRef != expectedRef) {
            return VerificationOutcome(
                valid = false,
                failureCode = VerificationFailureCode.WRONG_DEVICE,
                failureReason = "Command device_identity_ref mismatch"
            )
        }

        val issuedAtSec = PdsCanonicalCommandEnvelope.parseUtcIsoSeconds(claims.issuedAt, "issued_at")
        val notBeforeSec = PdsCanonicalCommandEnvelope.parseUtcIsoSeconds(claims.notBefore, "not_before")
        val expiresAtSec = PdsCanonicalCommandEnvelope.parseUtcIsoSeconds(claims.expiresAt, "expires_at")

        val keyNotBeforeSec = PdsCanonicalCommandEnvelope.parseUtcIsoSeconds(keyRecord.notBeforeIso, "key.not_before")
        val keyNotAfterSec = PdsCanonicalCommandEnvelope.parseUtcIsoSeconds(keyRecord.notAfterIso, "key.not_after")
        if (issuedAtSec < keyNotBeforeSec || issuedAtSec > keyNotAfterSec) {
            return VerificationOutcome(
                valid = false,
                failureCode = VerificationFailureCode.KID_OUTSIDE_VALIDITY,
                failureReason = "Command issued_at is outside signing key validity window"
            )
        }

        val nowSec = options.nowEpochSeconds
        val skew = options.maxClockSkewSeconds
        if (issuedAtSec > nowSec + skew || notBeforeSec > nowSec + skew) {
            return VerificationOutcome(
                valid = false,
                failureCode = VerificationFailureCode.FUTURE_ISSUED,
                failureReason = "Command issued_at or not_before is in the future beyond allowed clock skew"
            )
        }

        if (nowSec > expiresAtSec) {
            return VerificationOutcome(
                valid = false,
                failureCode = VerificationFailureCode.EXPIRED,
                failureReason = "Command has expired"
            )
        }

        if (
            PdsCanonicalCommandEnvelope.APPROVAL_REQUIRED_COMMANDS.contains(claims.type) &&
            claims.approvalRef.isNullOrBlank()
        ) {
            return VerificationOutcome(
                valid = false,
                failureCode = VerificationFailureCode.APPROVAL_REQUIRED,
                failureReason = "Sensitive command ${claims.type} requires approval_ref"
            )
        }

        return VerificationOutcome(
            valid = true,
            claims = claims,
            signatureB64 = parsed.signatureB64,
            keyRecord = keyRecord
        )
    }
}
