package com.iips.launcher.security.pds

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Durable in-flight enrollment metadata so a retry can resume the same challenge
 * instead of generating a second Keystore identity.
 */
class PdsPendingEnrollmentStore(
    private val storageFile: File
) {
    data class PendingEnrollment(
        val challengeId: String,
        val challengeNonce: String,
        val attestationChallengeB64: String,
        val attestationChallengeHashSha256: String,
        val tenantId: String,
        val publicKeyFingerprintSha256: String?,
        val hardwareFingerprintSha256: String,
        val issuedAt: String,
        val expiresAt: String,
        val enrollSignatureB64Url: String? = null
    ) {
        fun isExpired(nowEpochMilli: Long): Boolean {
            val expires = try {
                Instant.parse(expiresAt).toEpochMilli()
            } catch (_: Exception) {
                return true
            }
            return nowEpochMilli >= expires
        }

        fun toChallenge(): PdsEnrollmentCoordinator.ChallengeResponse {
            return PdsEnrollmentCoordinator.ChallengeResponse(
                challengeId = challengeId,
                challengeNonce = challengeNonce,
                attestationChallengeB64 = attestationChallengeB64,
                tenantId = tenantId,
                publicKeyFingerprintSha256 = publicKeyFingerprintSha256,
                hardwareFingerprintSha256 = hardwareFingerprintSha256,
                issuedAt = issuedAt,
                expiresAt = expiresAt
            )
        }
    }

    private val lock = Any()
    @Volatile
    private var state: PendingEnrollment? = null

    init {
        synchronized(lock) {
            state = loadLocked()
        }
    }

    fun current(): PendingEnrollment? {
        synchronized(lock) {
            return state
        }
    }

    fun persist(next: PendingEnrollment) {
        require(next.challengeId.isNotBlank()) { "challengeId is required" }
        require(next.attestationChallengeB64.isNotBlank()) { "attestation challenge is required" }
        require(next.tenantId.isNotBlank()) { "tenantId is required" }
        synchronized(lock) {
            state = next
            persistLocked(next)
        }
    }

    fun recordEnrollSignature(signatureB64Url: String) {
        synchronized(lock) {
            val current = state ?: return
            val next = current.copy(enrollSignatureB64Url = signatureB64Url)
            state = next
            persistLocked(next)
        }
    }

    fun clear() {
        synchronized(lock) {
            state = null
            if (storageFile.exists()) {
                storageFile.delete()
            }
        }
    }

    private fun loadLocked(): PendingEnrollment? {
        if (!storageFile.exists()) return null
        return try {
            val map = LinkedHashMap<String, String>()
            for (line in storageFile.readLines(StandardCharsets.UTF_8)) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val eq = trimmed.indexOf('=')
                if (eq <= 0) continue
                map[trimmed.substring(0, eq)] = trimmed.substring(eq + 1)
            }
            val challengeId = map["challenge_id"]?.takeIf { it.isNotBlank() } ?: return null
            val attestation = map["attestation_challenge_b64"]?.takeIf { it.isNotBlank() } ?: return null
            val tenantId = map["tenant_id"]?.takeIf { it.isNotBlank() } ?: return null
            PendingEnrollment(
                challengeId = challengeId,
                challengeNonce = map["challenge_nonce"].orEmpty(),
                attestationChallengeB64 = attestation,
                attestationChallengeHashSha256 = map["attestation_challenge_hash_sha256"].orEmpty(),
                tenantId = tenantId,
                publicKeyFingerprintSha256 = map["public_key_fingerprint_sha256"]?.takeIf { it.isNotBlank() },
                hardwareFingerprintSha256 = map["hardware_fingerprint_sha256"].orEmpty(),
                issuedAt = map["issued_at"].orEmpty(),
                expiresAt = map["expires_at"].orEmpty(),
                enrollSignatureB64Url = map["enroll_signature_b64url"]?.takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun persistLocked(next: PendingEnrollment) {
        val parent = storageFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmp = File(storageFile.parentFile, "${storageFile.name}.tmp")
        val sb = StringBuilder("# pds-pending-enrollment-v1\n")
        fun line(k: String, v: String) {
            sb.append(k).append('=').append(v.replace("\n", "").replace("\r", "")).append('\n')
        }
        line("challenge_id", next.challengeId)
        line("challenge_nonce", next.challengeNonce)
        line("attestation_challenge_b64", next.attestationChallengeB64)
        line("attestation_challenge_hash_sha256", next.attestationChallengeHashSha256)
        line("tenant_id", next.tenantId)
        line("public_key_fingerprint_sha256", next.publicKeyFingerprintSha256 ?: "")
        line("hardware_fingerprint_sha256", next.hardwareFingerprintSha256)
        line("issued_at", next.issuedAt)
        line("expires_at", next.expiresAt)
        line("enroll_signature_b64url", next.enrollSignatureB64Url ?: "")
        FileOutputStream(tmp).use { fos ->
            fos.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        Files.move(
            tmp.toPath(),
            storageFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    }
}
