package com.iips.launcher.security.pds

import java.io.File

class ConfirmedIdentityClient : PdsDeviceIdentityStatusClient {
    var probes: Int = 0
        private set

    override suspend fun probeRegisteredIdentity(
        deviceId: String,
        tenantId: String,
        enrollmentToken: String
    ): PdsDeviceIdentityProbe {
        probes += 1
        return PdsDeviceIdentityProbe(PdsDeviceIdentityStatusKind.CONFIRMED_PRESENT)
    }
}

class ScriptedIdentityClient(
    private val kind: PdsDeviceIdentityStatusKind,
    private val errorCode: String? = null
) : PdsDeviceIdentityStatusClient {
    var probes: Int = 0
        private set

    override suspend fun probeRegisteredIdentity(
        deviceId: String,
        tenantId: String,
        enrollmentToken: String
    ): PdsDeviceIdentityProbe {
        probes += 1
        return PdsDeviceIdentityProbe(kind, errorCode)
    }
}

class BindingAwareFakeProvisioner(
    private val binding: PdsAttestationBindingStore,
    var aliasExists: Boolean = false
) : PdsDeviceKeyProvisioner {
    var generateCalls: Int = 0
        private set
    var lastAttestationChallenge: ByteArray? = null
        private set

    override fun generateBoundToAttestationChallenge(attestationChallengeBytes: ByteArray) {
        if (attestationChallengeBytes.isEmpty()) {
            throw SecurityException("Attestation challenge bytes are required")
        }
        val hash = PdsEnrollmentCrypto.sha256Hex(attestationChallengeBytes)
        val decision = PdsKeystoreBindingPolicy.decide(aliasExists, binding.readHash(), hash)
        generateCalls += 1
        lastAttestationChallenge = attestationChallengeBytes.copyOf()
        if (decision == PdsKeystoreBindingPolicy.Decision.GENERATE) {
            aliasExists = true
            binding.writeHash(hash)
        }
    }

    override fun keyExists(): Boolean = aliasExists
}

fun testCoordinator(
    signer: PdsDeviceKeySigner,
    store: PdsEnrollmentStateStore,
    transport: PdsEnrollmentCoordinator.PdsEnrollmentTransport,
    tempDir: File,
    provisioner: PdsDeviceKeyProvisioner = NoopIfKeyPresentProvisioner(signer),
    identityStatus: PdsDeviceIdentityStatusClient = ConfirmedIdentityClient(),
    pendingStore: PdsPendingEnrollmentStore = PdsPendingEnrollmentStore(File(tempDir, "pending")),
    nowEpochMilli: () -> Long = { System.currentTimeMillis() }
): PdsEnrollmentCoordinator {
    return PdsEnrollmentCoordinator(
        signer = signer,
        provisioner = provisioner,
        stateStore = store,
        transport = transport,
        identityStatus = identityStatus,
        pendingStore = pendingStore,
        nowEpochMilli = nowEpochMilli
    )
}
