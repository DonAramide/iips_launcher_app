package com.iips.launcher.security.pds

/**
 * Test-only provisioner. Production enrollment must inject
 * [PdsAndroidKeystoreBoundProvisioner] explicitly.
 */
class NoopIfKeyPresentProvisioner(
    private val signer: PdsDeviceKeySigner
) : PdsDeviceKeyProvisioner {
    var lastAttestationChallenge: ByteArray? = null
        private set
    var generateCalls: Int = 0
        private set

    override fun generateBoundToAttestationChallenge(attestationChallengeBytes: ByteArray) {
        if (attestationChallengeBytes.isEmpty()) {
            throw SecurityException("Attestation challenge bytes are required")
        }
        lastAttestationChallenge = attestationChallengeBytes.copyOf()
        generateCalls += 1
    }

    override fun keyExists(): Boolean = true
}
