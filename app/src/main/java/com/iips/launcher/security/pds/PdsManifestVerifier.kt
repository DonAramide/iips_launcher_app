package com.iips.launcher.security.pds

import java.nio.charset.StandardCharsets
import java.security.PublicKey
import java.util.Locale
import java.util.TreeMap

/**
 * SPEC-04 signed provisioning-manifest verifier.
 *
 * Fail-closed: invalid signatures, unknown keys, expired manifests, wrong
 * device/tenant, version rollback, unauthorized adapters, and prohibited
 * payment-key material are all rejected.
 */
class PdsManifestVerifier(
    private val commandVerifier: PdsCommandVerifier = PdsCommandVerifier()
) {
    companion object {
        const val MANIFEST_JWS_TYP = "dotroid-manifest+jws"
        const val MANIFEST_PROTOCOL_VERSION = 1L
        const val MAX_MANIFEST_TTL_SECONDS = 7L * 24L * 60L * 60L
        private val SAFE_CONFIG_REF = Regex("^(cfg://|ref:)[A-Za-z0-9._:/-]+$")

        val APPROVED_ADAPTER_PACKAGES: Map<String, String> = mapOf(
            "topwise" to "com.dotroid.ee.adapter.topwise",
            "telpo" to "com.dotroid.ee.adapter.telpo",
            "nexgo" to "com.dotroid.ee.adapter.nexgo",
            "horizonpay" to "com.dotroid.ee.adapter.horizonpay",
            "dspread" to "com.dotroid.ee.adapter.dspread",
            "vanstone" to "com.dotroid.ee.adapter.vanstone",
            "morefun" to "com.dotroid.ee.adapter.morefun"
        )

        private val PROHIBITED_KEYS = setOf(
            "tmk", "tpk", "zpk", "zmk", "ctmk", "pin_block", "pinblock",
            "clear_key", "working_key", "master_key", "key_component",
            "decrypt_keys", "decryptkeys", "private_key", "jwt_secret",
            "access_token", "password", "client_secret", "secret_key"
        )

        private val REQUIRED_CLAIM_KEYS = listOf(
            "alg",
            "allowed_command_capabilities",
            "allowed_device_capabilities",
            "approved_ee_version",
            "approved_pds_version",
            "approved_vendor_adapter",
            "configuration_refs",
            "device_id",
            "device_identity_ref",
            "expires_at",
            "institution_config_ref",
            "issued_at",
            "key_id",
            "key_set_version",
            "manifest_id",
            "manifest_version",
            "protocol_version",
            "tenant_id",
            "terminal_assignment_ref"
        )

        fun assertNoProhibitedManifestMaterial(node: Any?, path: String = "manifest") {
            when (node) {
                null -> return
                is String -> {
                    val lower = node.lowercase(Locale.ROOT)
                    if (
                        lower.contains("decrypt_keys") ||
                        lower.contains("-----begin ") ||
                        lower.contains("ctmk=") ||
                        lower.contains("tmk=") ||
                        lower.contains("tpk=") ||
                        lower.contains("zpk=")
                    ) {
                        throw SecurityException(
                            "Prohibited cryptographic secret or key material detected in manifest string at $path"
                        )
                    }
                }
                is List<*> -> node.forEachIndexed { idx, item ->
                    assertNoProhibitedManifestMaterial(item, "$path[$idx]")
                }
                is Map<*, *> -> {
                    for ((k, v) in node) {
                        val key = (k as? String)?.trim()?.lowercase(Locale.ROOT) ?: ""
                        if (key in PROHIBITED_KEYS) {
                            throw SecurityException(
                                "Prohibited key material field \"$k\" in provisioning manifest at $path.$k (SPEC-04 §3)"
                            )
                        }
                        assertNoProhibitedManifestMaterial(v, "$path.$k")
                    }
                }
            }
        }
    }

    enum class FailureCode {
        MALFORMED_MANIFEST,
        UNSUPPORTED_PROTOCOL_VERSION,
        UNSUPPORTED_ALG,
        KID_MISSING,
        KID_UNKNOWN,
        KID_REVOKED,
        SIG_INVALID,
        WRONG_DEVICE,
        WRONG_TENANT,
        EXPIRED,
        FUTURE_ISSUED,
        VERSION_ROLLBACK,
        UNAUTHORIZED_ADAPTER,
        PROHIBITED_KEY_MATERIAL
    }

    data class VerificationOptions(
        val expectedDeviceId: String,
        val expectedDeviceIdentityRef: String? = null,
        val expectedTenantId: String,
        val expectedVendor: String? = null,
        val lastAcceptedManifestVersion: Long = 0L,
        val nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
        val maxClockSkewSeconds: Long = 60L
    )

    data class CanonicalManifestClaims(
        val alg: String,
        val allowedCommandCapabilities: List<String>,
        val allowedDeviceCapabilities: List<String>,
        val approvedEeVersion: Map<String, Any?>?,
        val approvedPdsVersion: Map<String, Any?>,
        val approvedVendorAdapter: Map<String, Any?>?,
        val configurationRefs: Map<String, String>,
        val deviceId: String,
        val deviceIdentityRef: String,
        val expiresAt: String,
        val institutionConfigRef: String,
        val issuedAt: String,
        val keyId: String,
        val keySetVersion: Long,
        val manifestId: String,
        val manifestVersion: Long,
        val protocolVersion: Long,
        val tenantId: String,
        val terminalAssignmentRef: Map<String, Any?>?
    )

    data class Outcome(
        val valid: Boolean,
        val failureCode: FailureCode? = null,
        val failureReason: String? = null,
        val claims: CanonicalManifestClaims? = null
    )

    fun applySignedKeySetJws(keysetJws: String, nowEpochSeconds: Long): Long {
        return commandVerifier.applySignedKeySetJws(keysetJws, nowEpochSeconds)
    }

    fun verifyManifestJws(manifestJws: String?, options: VerificationOptions): Outcome {
        if (manifestJws.isNullOrBlank()) {
            return Outcome(false, FailureCode.MALFORMED_MANIFEST, "manifest_jws must be a non-empty string")
        }
        val parts = manifestJws.trim().split(".")
        if (parts.size != 3) {
            return Outcome(false, FailureCode.MALFORMED_MANIFEST, "Compact manifest JWS must have 3 segments")
        }

        val headerBytes: ByteArray
        val payloadBytes: ByteArray
        val sigBytes: ByteArray
        try {
            headerBytes = PdsCanonicalCommandEnvelope.fromBase64UrlStrict(parts[0], "manifest.header")
            payloadBytes = PdsCanonicalCommandEnvelope.fromBase64UrlStrict(parts[1], "manifest.payload")
            sigBytes = PdsCanonicalCommandEnvelope.fromBase64UrlStrict(parts[2], "manifest.signature")
        } catch (e: Exception) {
            return Outcome(false, FailureCode.MALFORMED_MANIFEST, e.message)
        }
        if (sigBytes.size != 64) {
            return Outcome(false, FailureCode.MALFORMED_MANIFEST, "Invalid ES256 signature length")
        }

        val rawHeader: Map<String, Any?>
        val rawPayload: Any?
        try {
            @Suppress("UNCHECKED_CAST")
            rawHeader = PdsCanonicalCommandEnvelope.parseJsonRejectingDuplicateKeys(
                String(headerBytes, StandardCharsets.UTF_8)
            ) as Map<String, Any?>
            rawPayload = PdsCanonicalCommandEnvelope.parseJsonRejectingDuplicateKeys(
                String(payloadBytes, StandardCharsets.UTF_8)
            )
        } catch (e: Exception) {
            return Outcome(false, FailureCode.MALFORMED_MANIFEST, e.message)
        }

        if (rawHeader["alg"] != "ES256") {
            return Outcome(false, FailureCode.UNSUPPORTED_ALG, "Unsupported manifest alg: ${rawHeader["alg"]}")
        }
        if (rawHeader["v"] != 1L || rawHeader["typ"] != MANIFEST_JWS_TYP) {
            return Outcome(false, FailureCode.MALFORMED_MANIFEST, "Invalid manifest JWS header typ or version")
        }
        val headerKid = rawHeader["kid"] as? String
        if (headerKid.isNullOrBlank()) {
            return Outcome(false, FailureCode.KID_MISSING, "Missing manifest JWS header kid")
        }

        try {
            assertNoProhibitedManifestMaterial(rawPayload)
        } catch (e: Exception) {
            return Outcome(false, FailureCode.PROHIBITED_KEY_MATERIAL, e.message)
        }

        val payloadObj = rawPayload as? Map<String, Any?>
            ?: return Outcome(false, FailureCode.MALFORMED_MANIFEST, "Manifest claims must be a JSON object")
        if (payloadObj["protocol_version"] != MANIFEST_PROTOCOL_VERSION) {
            return Outcome(
                false,
                FailureCode.UNSUPPORTED_PROTOCOL_VERSION,
                "Unsupported protocol_version: ${payloadObj["protocol_version"]}"
            )
        }

        val claims: CanonicalManifestClaims
        try {
            claims = validateCanonicalManifestClaims(payloadObj)
        } catch (e: Exception) {
            val msg = e.message ?: "Malformed manifest claims"
            val code = when {
                msg.contains("Unauthorized vendor adapter") || msg.contains("Unauthorized adapter") ->
                    FailureCode.UNAUTHORIZED_ADAPTER
                msg.contains("Prohibited") -> FailureCode.PROHIBITED_KEY_MATERIAL
                else -> FailureCode.MALFORMED_MANIFEST
            }
            return Outcome(false, code, msg)
        }

        if (headerKid != claims.keyId) {
            return Outcome(false, FailureCode.MALFORMED_MANIFEST, "Manifest header kid does not match payload key_id")
        }

        val expectedHeaderB64 = PdsCanonicalCommandEnvelope.toBase64Url(
            PdsCanonicalCommandEnvelope.canonicalizeJson(
                TreeMap<String, Any?>().apply {
                    put("alg", "ES256")
                    put("kid", claims.keyId)
                    put("typ", MANIFEST_JWS_TYP)
                    put("v", 1L)
                }
            ).toByteArray(StandardCharsets.UTF_8)
        )
        val expectedPayloadB64 = PdsCanonicalCommandEnvelope.toBase64Url(
            PdsCanonicalCommandEnvelope.canonicalizeJson(claimsToCanonicalMap(payloadObj))
                .toByteArray(StandardCharsets.UTF_8)
        )
        if (parts[0] != expectedHeaderB64 || parts[1] != expectedPayloadB64) {
            return Outcome(false, FailureCode.MALFORMED_MANIFEST, "Non-canonical JSON serialization in manifest JWS")
        }

        val keyEntry = commandVerifier.getRegisteredKey(claims.keyId)
        if (keyEntry == null || keyEntry.purpose != PdsCommandVerifier.KeyPurpose.MANIFEST) {
            return Outcome(false, FailureCode.KID_UNKNOWN, "Unknown or non-manifest signing key: ${claims.keyId}")
        }
        if (keyEntry.status == PdsCommandVerifier.KeyStatus.REVOKED) {
            return Outcome(false, FailureCode.KID_REVOKED, "Manifest signing key ${claims.keyId} is revoked")
        }

        val publicKey: PublicKey = commandVerifier.getRegisteredPublicKey(claims.keyId)
            ?: return Outcome(false, FailureCode.KID_UNKNOWN, "Unknown manifest signing key: ${claims.keyId}")
        val signingInput = "${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII)
        if (!PdsCommandVerifier.verifyEs256Signature(publicKey, signingInput, sigBytes)) {
            return Outcome(false, FailureCode.SIG_INVALID, "Manifest ES256 signature verification failed")
        }

        if (claims.deviceId != options.expectedDeviceId.trim()) {
            return Outcome(false, FailureCode.WRONG_DEVICE, "Manifest device_id does not match this device")
        }
        val expectedRef = options.expectedDeviceIdentityRef?.trim()
        if (!expectedRef.isNullOrEmpty() && claims.deviceIdentityRef != expectedRef) {
            return Outcome(false, FailureCode.WRONG_DEVICE, "Manifest device_identity_ref does not match this device")
        }
        if (claims.tenantId != options.expectedTenantId.trim()) {
            return Outcome(false, FailureCode.WRONG_TENANT, "Manifest tenant_id does not match device tenant")
        }

        val expectedVendor = options.expectedVendor?.trim()?.lowercase(Locale.ROOT)
        val adapterVendor = (claims.approvedVendorAdapter?.get("vendor") as? String)?.lowercase(Locale.ROOT)
        if (!expectedVendor.isNullOrEmpty() && !adapterVendor.isNullOrEmpty() && adapterVendor != expectedVendor) {
            return Outcome(
                false,
                FailureCode.UNAUTHORIZED_ADAPTER,
                "Manifest adapter vendor ($adapterVendor) does not match terminal vendor ($expectedVendor)"
            )
        }

        val issuedSec = PdsCanonicalCommandEnvelope.parseUtcIsoSeconds(claims.issuedAt, "issued_at")
        val expiresSec = PdsCanonicalCommandEnvelope.parseUtcIsoSeconds(claims.expiresAt, "expires_at")
        if (issuedSec > options.nowEpochSeconds + options.maxClockSkewSeconds) {
            return Outcome(false, FailureCode.FUTURE_ISSUED, "Manifest issued_at is in the future beyond clock skew")
        }
        if (options.nowEpochSeconds > expiresSec) {
            return Outcome(false, FailureCode.EXPIRED, "Manifest has expired")
        }
        if (claims.manifestVersion < options.lastAcceptedManifestVersion) {
            return Outcome(
                false,
                FailureCode.VERSION_ROLLBACK,
                "Manifest version ${claims.manifestVersion} is older than last accepted version ${options.lastAcceptedManifestVersion}"
            )
        }

        return Outcome(valid = true, claims = claims)
    }

    @Suppress("UNCHECKED_CAST")
    private fun validateCanonicalManifestClaims(obj: Map<String, Any?>): CanonicalManifestClaims {
        val keys = obj.keys.sorted()
        if (keys.size != REQUIRED_CLAIM_KEYS.size || keys != REQUIRED_CLAIM_KEYS) {
            throw IllegalArgumentException("Manifest claims keys mismatch")
        }
        if (obj["alg"] != "ES256") {
            throw IllegalArgumentException("Unsupported manifest signature algorithm")
        }
        fun reqStr(name: String): String {
            val v = obj[name]
            if (v !is String || v.isEmpty() || v.trim() != v) {
                throw IllegalArgumentException("Invalid or empty string field \"$name\" in manifest")
            }
            return v
        }
        val keyId = reqStr("key_id")
        if (!keyId.startsWith("mfs-")) {
            throw IllegalArgumentException("Invalid manifest signing key_id \"$keyId\" (expected mfs-*)")
        }
        val manifestVersion = obj["manifest_version"] as? Long
            ?: throw IllegalArgumentException("manifest_version must be an integer >= 1")
        if (manifestVersion < 1L) throw IllegalArgumentException("manifest_version must be an integer >= 1")
        val keySetVersion = obj["key_set_version"] as? Long
            ?: throw IllegalArgumentException("key_set_version must be an integer >= 1")
        val issuedAt = reqStr("issued_at")
        val expiresAt = reqStr("expires_at")
        val issuedSec = PdsCanonicalCommandEnvelope.parseUtcIsoSeconds(issuedAt, "issued_at")
        val expiresSec = PdsCanonicalCommandEnvelope.parseUtcIsoSeconds(expiresAt, "expires_at")
        if (expiresSec <= issuedSec) {
            throw IllegalArgumentException("Manifest expires_at must be greater than issued_at")
        }
        if (expiresSec - issuedSec > MAX_MANIFEST_TTL_SECONDS) {
            throw IllegalArgumentException("Manifest TTL exceeds maximum of 7 days")
        }
        val institutionConfigRef = reqStr("institution_config_ref")
        if (!SAFE_CONFIG_REF.matches(institutionConfigRef)) {
            throw IllegalArgumentException("Invalid institution_config_ref format")
        }
        val configRefsRaw = obj["configuration_refs"] as? Map<*, *>
            ?: throw IllegalArgumentException("configuration_refs must be a JSON object")
        val configRefs = LinkedHashMap<String, String>()
        for ((k, v) in configRefsRaw) {
            val key = k as? String ?: throw IllegalArgumentException("Invalid configuration_refs key")
            if (v !is String || !SAFE_CONFIG_REF.matches(v)) {
                throw IllegalArgumentException("Invalid configuration_refs[\"$key\"]")
            }
            configRefs[key] = v
        }

        val adapter = obj["approved_vendor_adapter"]
        var adapterMap: Map<String, Any?>? = null
        if (adapter != null) {
            val ad = adapter as? Map<String, Any?>
                ?: throw IllegalArgumentException("approved_vendor_adapter must be an object or null")
            val vendor = ad["vendor"] as? String
            val pkg = ad["package_name"] as? String
            if (vendor.isNullOrBlank() || pkg.isNullOrBlank() || APPROVED_ADAPTER_PACKAGES[vendor] != pkg) {
                throw IllegalArgumentException("Unauthorized vendor adapter package \"$pkg\" for vendor \"$vendor\"")
            }
            adapterMap = ad
        }

        return CanonicalManifestClaims(
            alg = "ES256",
            allowedCommandCapabilities = (obj["allowed_command_capabilities"] as List<*>).map { it.toString() },
            allowedDeviceCapabilities = (obj["allowed_device_capabilities"] as List<*>).map { it.toString() },
            approvedEeVersion = obj["approved_ee_version"] as? Map<String, Any?>,
            approvedPdsVersion = obj["approved_pds_version"] as? Map<String, Any?>
                ?: throw IllegalArgumentException("approved_pds_version must be an object"),
            approvedVendorAdapter = adapterMap,
            configurationRefs = configRefs,
            deviceId = reqStr("device_id"),
            deviceIdentityRef = reqStr("device_identity_ref"),
            expiresAt = expiresAt,
            institutionConfigRef = institutionConfigRef,
            issuedAt = issuedAt,
            keyId = keyId,
            keySetVersion = keySetVersion,
            manifestId = reqStr("manifest_id"),
            manifestVersion = manifestVersion,
            protocolVersion = MANIFEST_PROTOCOL_VERSION,
            tenantId = reqStr("tenant_id"),
            terminalAssignmentRef = obj["terminal_assignment_ref"] as? Map<String, Any?>
        )
    }

    private fun claimsToCanonicalMap(obj: Map<String, Any?>): Map<String, Any?> {
        return TreeMap(obj)
    }
}
