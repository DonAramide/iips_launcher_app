package com.iips.launcher.mdm

import android.content.Context
import com.iips.launcher.utils.SecurePreferences

/**
 * Manages nonces to prevent replay attacks.
 * Maintains an LRU cache of recently seen nonces.
 */
object NonceManager {
    private const val MAX_NONCES = 100
    private val nonceCache = mutableListOf<String>()

    /**
     * Checks if a nonce has been seen before.
     * Returns true if replayed, false if new (and saves it).
     */
    fun isNonceReplayed(context: Context, nonce: String): Boolean {
        synchronized(nonceCache) {
            if (nonceCache.isEmpty()) {
                // Initialize from disk
                nonceCache.addAll(SecurePreferences.getRecentNonces(context))
            }
            
            if (nonceCache.contains(nonce)) {
                return true
            }
            
            // Add new nonce at the beginning
            nonceCache.add(0, nonce)
            
            // Trim to max size
            if (nonceCache.size > MAX_NONCES) {
                nonceCache.removeAt(nonceCache.size - 1)
            }
            
            // Persist to disk
            SecurePreferences.setRecentNonces(context, nonceCache)
            return false
        }
    }
}
