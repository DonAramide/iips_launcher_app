package com.iips.launcher.security

import android.util.Base64
import com.iips.launcher.security.SecurityUtils
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class TelemetryHmacSigner @Inject constructor() {
    private val SERVER_SALT = "iips_mdm_salt_2026"
    data class SignatureResult(
        val signature: String,
        val timestamp: String,
        val nonce: String
    )
    /**
     * Generates an HMAC-SHA256 signature for the given payload.
     * Follows the security protocol: Base64(HMAC_SHA256(payload + timestamp + nonce, derivedKey))
     */
    fun signPayload(payload: String, secret: String, timestamp: String, nonce: String): String {
        // Key Derivation matching server logic
        val derivedKey = SecurityUtils.sha256(secret + SERVER_SALT)
        
        val dataToHash = payload + timestamp + nonce
        val hmacKey = SecretKeySpec(derivedKey.toByteArray(), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey)
        val computedHash = mac.doFinal(dataToHash.toByteArray())
        return Base64.encodeToString(computedHash, Base64.NO_WRAP)
    }
}
