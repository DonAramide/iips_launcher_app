package com.iips.launcher.security.pds

import android.os.Build
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Phase 6.5.4.3A — debug-only, non-PEM attestation forensic capture.
 *
 * Inspects Android Keystore attestation certificates **before** Quasar verify
 * transport. Persists sanitized metadata only (fingerprints + validity windows).
 * Never exports private keys, PEM/DER bodies, SPKI, tokens, or challenges.
 *
 * Capture failures must never alter enrollment outcome.
 */
object PdsAttestationForensicCapture {
    const val CAPTURE_VERSION = 1
    const val EVENT = "pds_attestation_forensic_capture"
    const val KEY_DESCRIPTION_OID = "1.3.6.1.4.1.11129.2.1.17"
    const val STORE_DIR = "pds_attestation_forensic"
    const val LATEST_FILE = "latest.json"
    const val HISTORY_FILE = "history.jsonl"
    const val MAX_HISTORY_BYTES = 64 * 1024
    const val MAX_CERT_RECORDS = 16

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    private val gsonCompact: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val writeLock = Any()

    data class CertificateRecord(
        @SerializedName("event") val event: String = EVENT,
        @SerializedName("capture_version") val captureVersion: Int = CAPTURE_VERSION,
        @SerializedName("manufacturer") val manufacturer: String? = null,
        @SerializedName("model") val model: String? = null,
        @SerializedName("android_api") val androidApi: Int? = null,
        @SerializedName("certificate_index") val certificateIndex: Int,
        @SerializedName("certificate_chain_length") val certificateChainLength: Int,
        @SerializedName("der_sha256") val derSha256: String,
        @SerializedName("not_before_utc") val notBeforeUtc: String,
        @SerializedName("not_after_utc") val notAfterUtc: String,
        @SerializedName("evaluated_at_utc") val evaluatedAtUtc: String,
        @SerializedName("not_yet_valid") val notYetValid: Boolean,
        @SerializedName("expired") val expired: Boolean,
        @SerializedName("validity_inverted") val validityInverted: Boolean,
        @SerializedName("certificate_type") val certificateType: String,
        @SerializedName("key_description_present") val keyDescriptionPresent: Boolean,
    )

    data class KeystoreEvidence(
        @SerializedName("alias_present") val aliasPresent: Boolean,
        @SerializedName("chain_retrieved") val chainRetrieved: Boolean,
        @SerializedName("chain_length") val chainLength: Int,
        @SerializedName("is_inside_secure_hardware") val isInsideSecureHardware: Boolean?,
        @SerializedName("security_level_name") val securityLevelName: String?,
        @SerializedName("key_algorithm") val keyAlgorithm: String?,
        @SerializedName("key_size_bits") val keySizeBits: Int?,
        @SerializedName("strongbox_applicable") val strongBoxApplicable: Boolean,
        @SerializedName("strongbox_backed") val strongBoxBacked: Boolean?,
        @SerializedName("attestation_challenge_configured") val attestationChallengeConfigured: Boolean,
        @SerializedName("inspection_source") val inspectionSource: String?,
    )

    data class DeviceProfile(
        @SerializedName("manufacturer") val manufacturer: String,
        @SerializedName("model") val model: String,
        @SerializedName("android_api") val androidApi: Int,
        @SerializedName("build_fingerprint_redacted") val buildFingerprintRedacted: String,
        @SerializedName("build_tags") val buildTags: String,
        @SerializedName("build_type") val buildType: String,
        @SerializedName("security_patch_level") val securityPatchLevel: String,
    )

    data class CaptureReport(
        @SerializedName("event") val event: String = EVENT,
        @SerializedName("capture_version") val captureVersion: Int = CAPTURE_VERSION,
        @SerializedName("evaluated_at_utc") val evaluatedAtUtc: String,
        @SerializedName("device") val device: DeviceProfile,
        @SerializedName("keystore") val keystore: KeystoreEvidence,
        @SerializedName("certificates") val certificates: List<CertificateRecord>,
        @SerializedName("notes") val notes: List<String> = emptyList(),
    )

    /**
     * Testable enable override. Production callers leave this null so the
     * build-type [PdsAttestationForensicGate] controls enablement.
     */
    @Volatile
    var enableOverrideForTests: Boolean? = null

    fun isCaptureEnabled(): Boolean {
        enableOverrideForTests?.let { return it }
        return PdsAttestationForensicGate.isEnabled()
    }

    /**
     * Capture from Base64 DER chain (exact bytes about to be sent as
     * `certificate_chain_b64`). Never persists the DER itself.
     *
     * @return true if a report was written; false if disabled or on soft failure.
     */
    fun captureBeforeQuasarTransmit(
        outputDir: File,
        chainB64: List<String>,
        device: DeviceProfile,
        keystore: KeystoreEvidence,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!isCaptureEnabled()) return false
        return try {
            val evaluatedAt = formatUtcIso(Date(nowMillis))
            val certs = decodeChain(chainB64)
            val records = inspectCertificates(
                certificates = certs,
                nowMillis = nowMillis,
                manufacturer = device.manufacturer,
                model = device.model,
                androidApi = device.androidApi,
            )
            val notes = mutableListOf<String>()
            if (certs.isEmpty()) {
                notes += "certificate_chain_empty_or_undecodable"
            }
            val report = CaptureReport(
                evaluatedAtUtc = evaluatedAt,
                device = device,
                keystore = keystore.copy(
                    chainRetrieved = certs.isNotEmpty(),
                    chainLength = certs.size,
                ),
                certificates = records,
                notes = notes,
            )
            persistReport(outputDir, report)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun inspectCertificates(
        certificates: List<X509Certificate>,
        nowMillis: Long = System.currentTimeMillis(),
        manufacturer: String? = null,
        model: String? = null,
        androidApi: Int? = null,
    ): List<CertificateRecord> {
        if (certificates.isEmpty()) return emptyList()
        val now = Date(nowMillis)
        val evaluatedAt = formatUtcIso(now)
        val chainLen = certificates.size
        return certificates.take(MAX_CERT_RECORDS).mapIndexed { index, cert ->
            inspectOne(
                index = index,
                chainLength = chainLen,
                cert = cert,
                now = now,
                evaluatedAtUtc = evaluatedAt,
                manufacturer = manufacturer,
                model = model,
                androidApi = androidApi,
            )
        }
    }

    fun inspectOne(
        index: Int,
        chainLength: Int,
        cert: X509Certificate,
        now: Date,
        evaluatedAtUtc: String,
        manufacturer: String? = null,
        model: String? = null,
        androidApi: Int? = null,
    ): CertificateRecord {
        val der = try {
            cert.encoded
        } catch (e: Exception) {
            throw CertificateInspectionException("certificate_encoding_failed:$index", e)
        }
        val notBefore = cert.notBefore
        val notAfter = cert.notAfter
        val notYetValid = notBefore.after(now)
        val expired = notAfter.before(now)
        val inverted = !notAfter.after(notBefore)
        return CertificateRecord(
            manufacturer = manufacturer,
            model = model,
            androidApi = androidApi,
            certificateIndex = index,
            certificateChainLength = chainLength,
            derSha256 = sha256Hex(der),
            notBeforeUtc = formatUtcIso(notBefore),
            notAfterUtc = formatUtcIso(notAfter),
            evaluatedAtUtc = evaluatedAtUtc,
            notYetValid = notYetValid,
            expired = expired,
            validityInverted = inverted,
            certificateType = cert.type,
            keyDescriptionPresent = hasKeyDescriptionExtension(cert),
        )
    }

    fun hasKeyDescriptionExtension(cert: X509Certificate): Boolean {
        val critical = cert.criticalExtensionOIDs ?: emptySet()
        val nonCritical = cert.nonCriticalExtensionOIDs ?: emptySet()
        return KEY_DESCRIPTION_OID in critical || KEY_DESCRIPTION_OID in nonCritical
    }

    fun sha256Hex(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    fun formatUtcIso(date: Date): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(date)
    }

    fun redactBuildFingerprint(fingerprint: String): String {
        return fingerprint
            .replace(Regex("/\\d{6,}:"), "/<build_id>:")
            .replace(Regex(":\\d{8,}"), ":<id>")
    }

    fun keySizeBits(publicKey: PublicKey?): Int? {
        if (publicKey == null) return null
        return when (publicKey) {
            is ECPublicKey -> publicKey.params?.order?.bitLength()
            is RSAPublicKey -> publicKey.modulus?.bitLength()
            else -> null
        }
    }

    fun decodeChain(chainB64: List<String>): List<X509Certificate> {
        if (chainB64.isEmpty()) return emptyList()
        val factory = CertificateFactory.getInstance("X.509")
        val out = ArrayList<X509Certificate>(chainB64.size)
        for (b64 in chainB64) {
            val trimmed = b64.trim()
            if (trimmed.isEmpty()) continue
            val der = try {
                Base64.getDecoder().decode(trimmed)
            } catch (_: IllegalArgumentException) {
                continue
            }
            val cert = factory.generateCertificate(der.inputStream()) as? X509Certificate
                ?: continue
            out += cert
        }
        return out
    }

    fun toJson(report: CaptureReport): String = gson.toJson(report)

    fun toCompactJson(report: CaptureReport): String = gsonCompact.toJson(report)

    fun assertNoSecrets(json: String) {
        val lower = json.lowercase(Locale.US)
        val forbidden = listOf(
            "begin certificate",
            "begin private",
            "-----begin",
            "certificate_chain_b64",
            "device_public_key_spki",
            "enrollment_token",
            "challenge_nonce",
            "\"password\"",
            "authorization",
            "bearer ",
        )
        for (token in forbidden) {
            check(!lower.contains(token)) { "forbidden_secret_marker:$token" }
        }
        check(!Regex("[A-Za-z0-9+/]{200,}={0,2}").containsMatchIn(json)) {
            "forbidden_long_base64_blob"
        }
    }

    private fun persistReport(outputDir: File, report: CaptureReport) {
        synchronized(writeLock) {
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                throw IllegalStateException("forensic_dir_create_failed")
            }
            val json = toJson(report)
            assertNoSecrets(json)
            atomicWrite(File(outputDir, LATEST_FILE), json)
            appendHistoryBounded(File(outputDir, HISTORY_FILE), toCompactJson(report))
        }
    }

    fun atomicWrite(target: File, contents: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(contents, StandardCharsets.UTF_8)
        if (target.exists()) {
            target.delete()
        }
        if (!tmp.renameTo(target)) {
            target.writeText(contents, StandardCharsets.UTF_8)
            tmp.delete()
        }
    }

    fun appendHistoryBounded(historyFile: File, compactJsonLine: String) {
        val line = compactJsonLine.trim() + "\n"
        historyFile.appendText(line, StandardCharsets.UTF_8)
        if (historyFile.length() <= MAX_HISTORY_BYTES) return
        val kept = historyFile.readLines(StandardCharsets.UTF_8).takeLast(8)
        atomicWrite(historyFile, kept.joinToString("\n", postfix = "\n"))
    }

    fun deviceProfileFromBuild(
        manufacturer: String,
        model: String,
    ): DeviceProfile {
        val api = Build.VERSION.SDK_INT
        return DeviceProfile(
            manufacturer = manufacturer,
            model = model,
            androidApi = api,
            buildFingerprintRedacted = redactBuildFingerprint(Build.FINGERPRINT ?: ""),
            buildTags = Build.TAGS ?: "",
            buildType = Build.TYPE ?: "",
            securityPatchLevel = if (api >= 23) {
                Build.VERSION.SECURITY_PATCH ?: ""
            } else {
                ""
            },
        )
    }

    fun keystoreEvidenceFromSigner(
        signer: PdsDeviceKeySigner,
        chainLength: Int,
        attestationChallengeConfigured: Boolean,
    ): KeystoreEvidence {
        val backing = try {
            signer.inspectHardwareBacking()
        } catch (_: Throwable) {
            null
        }
        val api = Build.VERSION.SDK_INT
        val strongBoxApplicable = api >= Build.VERSION_CODES.P
        var algorithm: String? = null
        var size: Int? = null
        try {
            val spki = Base64.getDecoder().decode(signer.publicKeySpkiB64().trim())
            val key = java.security.KeyFactory
                .getInstance("EC")
                .generatePublic(X509EncodedKeySpec(spki))
            algorithm = key.algorithm
            size = keySizeBits(key)
        } catch (_: Throwable) {
            // leave nulls
        }
        return KeystoreEvidence(
            aliasPresent = backing?.inspectionSource != "alias_missing",
            chainRetrieved = chainLength > 0,
            chainLength = chainLength,
            isInsideSecureHardware = backing?.isInsideSecureHardware,
            securityLevelName = backing?.securityLevelName,
            keyAlgorithm = algorithm,
            keySizeBits = size,
            strongBoxApplicable = strongBoxApplicable,
            strongBoxBacked = if (strongBoxApplicable) {
                try {
                    signer.isStrongBoxBacked()
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            },
            attestationChallengeConfigured = attestationChallengeConfigured,
            inspectionSource = backing?.inspectionSource,
        )
    }

    class CertificateInspectionException(
        message: String,
        cause: Throwable? = null,
    ) : RuntimeException(message, cause)
}
