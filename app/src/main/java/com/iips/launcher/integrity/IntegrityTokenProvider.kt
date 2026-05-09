package com.iips.launcher.integrity

import android.util.Base64
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides nonces and manages the lifecycle of integrity tokens.
 */
@Singleton
class IntegrityTokenProvider @Inject constructor(
    private val integrityManager: IntegrityManager
) {
    private val secureRandom = SecureRandom()

    /**
     * Generates a fresh nonce for integrity requests.
     */
    fun generateNonce(): String {
        val nonce = ByteArray(16)
        secureRandom.nextBytes(nonce)
        return Base64.encodeToString(nonce, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    /**
     * Fetches a fresh integrity token.
     */
    suspend fun getFreshToken(): String? {
        val nonce = generateNonce()
        return integrityManager.requestIntegrityToken(nonce)
    }
}
