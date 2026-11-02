package com.iips.launcher.security.pds

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
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
) : PdsDeviceKeySigner {

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
         * Canonical request signing string per Phase 1C.2:
         * `pds-req-v1\nMETHOD\nPATH\nquery\nsha256hex(body)\ndevice_id\nkid\nts\nnonce`
         */
        fun buildCanonicalRequestSigningBytes(
            method: String,
            path: String,
            canonicalQuery: String,
            bodyBytes: ByteArray,
            deviceId: String,
            keyId: String,
            timestampSec: Long,
            nonceB64Url: String
        ): ByteArray {
            return PdsEnrollmentCrypto.buildDeviceRequestSigningBytes(
                PdsEnrollmentCrypto.DeviceRequestSigningParams(
                    method = method,
                    path = path,
                    canonicalQuery = canonicalQuery,
                    bodyBytes = bodyBytes,
                    deviceId = deviceId,
                    keyId = keyId,
                    timestampSec = timestampSec,
                    nonce = nonceB64Url
                )
            )
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

        if (!ks.containsAlias(keyAlias)) {
            val challengeBytes = if (!enrollmentToken.isNullOrBlank()) {
                sha256Bytes(enrollmentToken.trim().toByteArray(StandardCharsets.UTF_8))
            } else {
                null
            }
            if (hasStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    generateKeyPairInternal(challengeBytes, useStrongBox = true)
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

        val backing = inspectHardwareBacking()
        return ProvisionedDeviceIdentityKey(
            keyAlias = keyAlias,
            publicKeySpkiB64 = pubSpkiB64,
            attestationChainB64 = chain,
            isStrongBoxBacked = backing.isStrongBox
        )
    }

    override fun keyAlias(): String = keyAlias

    override fun publicKeySpkiB64(): String {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val cert = ks.getCertificate(keyAlias)
            ?: throw IllegalStateException("AndroidKeyStore missing certificate for $keyAlias")
        return Base64.getEncoder().encodeToString(cert.publicKey.encoded)
    }

    override fun publicKeyFingerprintSha256(): String {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val cert = ks.getCertificate(keyAlias)
            ?: throw IllegalStateException("AndroidKeyStore missing certificate for $keyAlias")
        return PdsEnrollmentCrypto.publicKeyFingerprintSha256(cert.publicKey.encoded)
    }

    override fun signPayload(payloadBytes: ByteArray): String = signWithDeviceKey(payloadBytes)

    override fun attestationChainB64(): List<String> {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return ks.getCertificateChain(keyAlias)?.map {
            Base64.getEncoder().encodeToString(it.encoded)
        } ?: emptyList()
    }

    override fun isStrongBoxBacked(): Boolean = inspectHardwareBacking().isStrongBox

    override fun isHardwareBacked(): Boolean = inspectHardwareBacking().isInsideSecureHardware

    override fun inspectHardwareBacking(): PdsHardwareBackingClassifier.HardwareBackingInfo {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(keyAlias)) {
            return PdsHardwareBackingClassifier.classify(
                alias = keyAlias,
                apiLevel = Build.VERSION.SDK_INT,
                isInsideSecureHardware = null,
                securityLevelApi31 = null,
                aliasPresent = false
            )
        }
        val entry = ks.getEntry(keyAlias, null) as? KeyStore.PrivateKeyEntry
            ?: return PdsHardwareBackingClassifier.classify(
                alias = keyAlias,
                apiLevel = Build.VERSION.SDK_INT,
                isInsideSecureHardware = null,
                securityLevelApi31 = null,
                aliasPresent = true
            )
        return try {
            val factory = KeyFactory.getInstance(entry.privateKey.algorithm, ANDROID_KEYSTORE)
            val info = factory.getKeySpec(entry.privateKey, KeyInfo::class.java)
            val securityLevel = if (Build.VERSION.SDK_INT >= 31) {
                info.securityLevel
            } else {
                null
            }
            PdsHardwareBackingClassifier.classify(
                alias = keyAlias,
                apiLevel = Build.VERSION.SDK_INT,
                isInsideSecureHardware = info.isInsideSecureHardware,
                securityLevelApi31 = securityLevel,
                aliasPresent = true
            )
        } catch (_: Exception) {
            PdsHardwareBackingClassifier.classify(
                alias = keyAlias,
                apiLevel = Build.VERSION.SDK_INT,
                isInsideSecureHardware = null,
                securityLevelApi31 = null,
                aliasPresent = true
            )
        }
    }

    /**
     * Device private keys are non-exportable. Application code cannot obtain
     * PKCS#8 bytes from Android Keystore or from this interface.
     */
    override fun exportPrivateKeyBytes(): ByteArray {
        throw UnsupportedOperationException(
            "PDS device private keys are non-exportable (Android Keystore / SPEC-03 §3)"
        )
    }

    fun signWithDeviceKey(payloadBytes: ByteArray): String {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = ks.getEntry(keyAlias, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("Device private key entry not found in AndroidKeyStore")
        if (entry.privateKey.encoded != null) {
            throw SecurityException("Android Keystore returned exportable private key material")
        }
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
