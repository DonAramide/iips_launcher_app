package com.iips.launcher.security.pds

import android.content.Context
import java.io.File

/**
 * Production-only Keystore provisioner. Test doubles must be constructed
 * explicitly in the test source set and never used as a coordinator default.
 */
class PdsAndroidKeystoreBoundProvisioner(
    private val context: Context,
    private val keyManager: PdsDeviceKeyManager
) : PdsDeviceKeyProvisioner {
    override fun generateBoundToAttestationChallenge(attestationChallengeBytes: ByteArray) {
        keyManager.generateBoundToAttestationChallenge(context, attestationChallengeBytes)
    }

    override fun keyExists(): Boolean = keyManager.keyExists()
}

object PdsProductionEnrollmentWiring {
    fun androidKeystoreProvisioner(
        context: Context,
        bindingFile: File = File(context.filesDir, "pds_key_attestation.bind")
    ): Pair<PdsDeviceKeyManager, PdsDeviceKeyProvisioner> {
        val keyManager = PdsDeviceKeyManager(attestationBindingFile = bindingFile)
        val provisioner = PdsAndroidKeystoreBoundProvisioner(context, keyManager)
        requireProductionProvisioner(provisioner)
        return keyManager to provisioner
    }

    fun requireProductionProvisioner(provisioner: PdsDeviceKeyProvisioner) {
        if (provisioner is PdsAndroidKeystoreBoundProvisioner) {
            return
        }
        throw SecurityException(
            "Production enrollment requires ${PdsAndroidKeystoreBoundProvisioner::class.java.simpleName}; " +
                "refusing ${provisioner.javaClass.name}"
        )
    }
}
