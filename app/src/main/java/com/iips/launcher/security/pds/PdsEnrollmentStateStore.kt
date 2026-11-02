package com.iips.launcher.security.pds

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-safe enrollment state for SPEC-03 / SPEC-04.
 *
 * Persists `device_id`, `device_identity_ref`, `device_key_id`, `tenant_id`,
 * lifecycle, and last-applied manifest version using fsync + atomic rename
 * so a process restart recovers enrollment without re-enrolling.
 */
class PdsEnrollmentStateStore(
    private val storageFile: File
) {
    data class EnrollmentState(
        val deviceId: String,
        val deviceIdentityRef: String,
        val deviceKeyId: String,
        val tenantId: String,
        val keyVersion: Long,
        val publicKeyFingerprintSha256: String,
        val lifecycleState: String,
        val attestationTrustTier: String,
        val allowedCapabilities: List<String>,
        val allowedCommands: List<String>,
        val enrolledAtIso: String,
        val lastAppliedManifestVersion: Long,
        val lastAppliedManifestId: String?,
        val lastAppliedManifestJwsSha256: String?
    ) {
        fun isEnrolled(): Boolean {
            return lifecycleState in setOf("REGISTERED", "ACTIVE", "LOCKED")
        }

        fun canAuthenticate(): Boolean = isEnrolled()

        fun canReceiveProvisioning(): Boolean = isEnrolled()
    }

    private val lock = Any()
    @Volatile
    private var state: EnrollmentState? = null

    init {
        synchronized(lock) {
            state = loadFromDiskLocked()
        }
    }

    fun current(): EnrollmentState? {
        synchronized(lock) {
            return state
        }
    }

    fun persistEnrollment(next: EnrollmentState) {
        require(next.deviceId.isNotBlank()) { "deviceId is required" }
        require(next.tenantId.isNotBlank()) { "tenantId is required" }
        synchronized(lock) {
            state = next
            persistToDiskLocked(next)
        }
    }

    fun recordAppliedManifest(manifestId: String, manifestVersion: Long, manifestJwsSha256: String) {
        synchronized(lock) {
            val current = state ?: throw IllegalStateException("Cannot apply manifest before enrollment")
            if (manifestVersion < current.lastAppliedManifestVersion) {
                throw SecurityException(
                    "Manifest version rollback rejected: $manifestVersion < ${current.lastAppliedManifestVersion}"
                )
            }
            val next = current.copy(
                lastAppliedManifestVersion = manifestVersion,
                lastAppliedManifestId = manifestId,
                lastAppliedManifestJwsSha256 = manifestJwsSha256
            )
            state = next
            persistToDiskLocked(next)
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

    private fun loadFromDiskLocked(): EnrollmentState? {
        if (!storageFile.exists()) return null
        val map = LinkedHashMap<String, String>()
        for (line in storageFile.readLines(StandardCharsets.UTF_8)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            map[trimmed.substring(0, eq)] = trimmed.substring(eq + 1)
        }
        val deviceId = map["device_id"] ?: return null
        val tenantId = map["tenant_id"] ?: return null
        return EnrollmentState(
            deviceId = deviceId,
            deviceIdentityRef = map["device_identity_ref"] ?: return null,
            deviceKeyId = map["device_key_id"] ?: return null,
            tenantId = tenantId,
            keyVersion = map["key_version"]?.toLongOrNull() ?: 1L,
            publicKeyFingerprintSha256 = map["public_key_fingerprint_sha256"] ?: "",
            lifecycleState = map["lifecycle_state"] ?: "PENDING_ENROLLMENT",
            attestationTrustTier = map["attestation_trust_tier"] ?: "software",
            allowedCapabilities = splitList(map["allowed_capabilities"]),
            allowedCommands = splitList(map["allowed_commands"]),
            enrolledAtIso = map["enrolled_at"] ?: "",
            lastAppliedManifestVersion = map["last_applied_manifest_version"]?.toLongOrNull() ?: 0L,
            lastAppliedManifestId = map["last_applied_manifest_id"]?.takeIf { it.isNotBlank() },
            lastAppliedManifestJwsSha256 = map["last_applied_manifest_jws_sha256"]?.takeIf { it.isNotBlank() }
        )
    }

    private fun persistToDiskLocked(next: EnrollmentState) {
        val parent = storageFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmpFile = File(storageFile.parentFile, "${storageFile.name}.tmp")
        val sb = StringBuilder("# pds-enrollment-state-v1\n")
        fun line(k: String, v: String) {
            sb.append(k).append('=').append(v.replace("\n", "").replace("\r", "")).append('\n')
        }
        line("device_id", next.deviceId)
        line("device_identity_ref", next.deviceIdentityRef)
        line("device_key_id", next.deviceKeyId)
        line("tenant_id", next.tenantId)
        line("key_version", next.keyVersion.toString())
        line("public_key_fingerprint_sha256", next.publicKeyFingerprintSha256)
        line("lifecycle_state", next.lifecycleState)
        line("attestation_trust_tier", next.attestationTrustTier)
        line("allowed_capabilities", next.allowedCapabilities.joinToString(","))
        line("allowed_commands", next.allowedCommands.joinToString(","))
        line("enrolled_at", next.enrolledAtIso)
        line("last_applied_manifest_version", next.lastAppliedManifestVersion.toString())
        line("last_applied_manifest_id", next.lastAppliedManifestId ?: "")
        line("last_applied_manifest_jws_sha256", next.lastAppliedManifestJwsSha256 ?: "")
        FileOutputStream(tmpFile).use { fos ->
            fos.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        Files.move(
            tmpFile.toPath(),
            storageFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    }

    private fun splitList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
