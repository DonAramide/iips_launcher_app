package com.iips.launcher.security.pds

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * SPEC-02 §1 & §3 — Android Keystore Device Key & Request Signer.
 *
 * - Alias: `pds_device_key_v1`
 * - Algorithm: EC P-256 (`secp256r1`), `PURPOSE_SIGN`, `DIGEST_SHA256`
 * - Private key never leaves Android Keystore and is never exposed to EE or vendor adapters.
 */
class PdsDeviceKeyManager(
    private val keyAlias: String = DEFAULT_DEVICE_KEY_ALIAS
) {
    companion object {
        const val DEFAULT_DEVICE_KEY_ALIAS = "pds_device_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        fun sha256Bytes(input: ByteArray): ByteArray {
            return MessageDigest.getInstance("SHA-256").digest(input)
        }

        fun sha256Hex(input: ByteArray): String {
            return sha256Bytes(input).joinToString("") { "%02x".format(it) }
        }

        /**
         * Canonical request signing string per SPEC-02 §3:
         * `METHOD\n/path\ncanonicalQuery\nhex(SHA-256(bodyBytes))\nts\nnonce`
         */
        fun buildCanonicalRequestSigningBytes(
            method: String,
            path: String,
            canonicalQuery: String,
            bodyBytes: ByteArray,
            timestampSec: Long,
            nonceB64Url: String
        ): ByteArray {
            val bodyHashHex = sha256Hex(bodyBytes)
            val canonical = listOf(
                method.trim().uppercase(),
                path.trim(),
                canonicalQuery.trim(),
                bodyHashHex,
                timestampSec.toString(),
                nonceB64Url.trim()
            ).joinToString("\n")
            return canonical.toByteArray(StandardCharsets.UTF_8)
        }
    }

    data class ProvisionedDeviceIdentityKey(
        val keyAlias: String,
        val publicKeySpkiB64: String,
        val attestationChainB64: List<String>,
        val isStrongBoxBacked: Boolean
    )

    fun ensureDeviceKey(context: Context, enrollmentToken: String? = null): ProvisionedDeviceIdentityKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val hasStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

        var strongBoxUsed = false
        if (!ks.containsAlias(keyAlias)) {
            val challengeBytes = if (!enrollmentToken.isNullOrBlank()) {
                sha256Bytes(enrollmentToken.trim().toByteArray(StandardCharsets.UTF_8))
            } else {
                null
            }
            if (hasStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    generateKeyPairInternal(challengeBytes, useStrongBox = true)
                    strongBoxUsed = true
                } catch (_: Exception) {
                    generateKeyPairInternal(challengeBytes, useStrongBox = false)
                }
            } else {
                generateKeyPairInternal(challengeBytes, useStrongBox = false)
            }
        }

        val cert = ks.getCertificate(keyAlias)
            ?: throw IllegalStateException("AndroidKeyStore missing certificate for $keyAlias")
        val pubSpkiB64 = Base64.getEncoder().encodeToString(cert.publicKey.encoded)
        val chain = ks.getCertificateChain(keyAlias)?.map {
            Base64.getEncoder().encodeToString(it.encoded)
        } ?: listOf(Base64.getEncoder().encodeToString(cert.encoded))

        return ProvisionedDeviceIdentityKey(
            keyAlias = keyAlias,
            publicKeySpkiB64 = pubSpkiB64,
            attestationChainB64 = chain,
            isStrongBoxBacked = strongBoxUsed
        )
    }

    /**
     * Signs an arbitrary canonical byte payload using the non-exportable Android Keystore EC P-256 key
     * and returns a base64url-encoded DER signature (`SPEC-02` §3).
     */
    fun signWithDeviceKey(payloadBytes: ByteArray): String {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = ks.getEntry(keyAlias, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("Device private key entry not found in AndroidKeyStore")
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(entry.privateKey)
        signer.update(payloadBytes)
        val derSig = signer.sign()
        return PdsCanonicalCommandEnvelope.toBase64Url(derSig)
    }

    private fun generateKeyPairInternal(challengeBytes: ByteArray?, useStrongBox: Boolean) {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)

        if (challengeBytes != null) {
            builder.setAttestationChallenge(challengeBytes)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(false)
            if (useStrongBox) {
                builder.setIsStrongBoxBacked(true)
            }
        }
        kpg.initialize(builder.build())
        kpg.generateKeyPair()
    }
}
