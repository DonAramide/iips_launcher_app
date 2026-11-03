package com.iips.launcher.security.pds

/**
 * Fail-closed attestation-binding rules for an existing Android Keystore alias.
 * An existing key is never assumed to belong to the current server challenge.
 */
object PdsKeystoreBindingPolicy {
    enum class Decision {
        GENERATE,
        REUSE
    }

    fun decide(
        aliasExists: Boolean,
        storedBindingHash: String?,
        currentChallengeHash: String
    ): Decision {
        if (currentChallengeHash.isBlank()) {
            throw SecurityException("Attestation challenge binding hash is required")
        }
        if (!aliasExists) {
            return Decision.GENERATE
        }
        val stored = storedBindingHash?.trim().orEmpty()
        if (stored.isEmpty()) {
            throw SecurityException(
                "Existing Keystore alias has no attestation binding record; refusing silent reuse"
            )
        }
        if (!stored.equals(currentChallengeHash, ignoreCase = true)) {
            throw SecurityException(
                "Existing Keystore alias was not generated against this server attestation challenge; authorized rotation required"
            )
        }
        return Decision.REUSE
    }
}
