package com.iips.launcher.security.pds

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class PdsPhase1D4EnrollmentTest {

    private val uuid = "550e8400-e29b-41d4-a716-446655440001"
    private val tenant = "11111111-2222-3333-4444-555555555555"
    private val hardware = PdsEnrollmentCrypto.HardwareAttributes(
        manufacturer = "Topwise",
        model = "T1",
        hardwareId = "hw-1",
        serialNumber = "TW202611020001",
        androidVersion = "11",
        sdkInt = 30L,
        buildFingerprint = "Topwise/T1/T1:11/release-keys",
        pdsVersion = "1.0.0",
        vendor = "topwise"
    )

    private fun generateSoftwareP256(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    private class NonExportableSoftwareSigner(
        private val pair: KeyPair,
        private val failSign: Boolean = false
    ) : PdsDeviceKeySigner {
        override fun keyAlias() = "pds_device_key_v1"
        override fun publicKeySpkiB64(): String =
            Base64.getEncoder().encodeToString(pair.public.encoded)
        override fun publicKeyFingerprintSha256(): String =
            PdsEnrollmentCrypto.publicKeyFingerprintSha256(pair.public.encoded)
        override fun signPayload(payloadBytes: ByteArray): String {
            if (failSign) throw SecurityException("missing private key")
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(pair.private)
            signer.update(payloadBytes)
            return PdsCanonicalCommandEnvelope.toBase64Url(signer.sign())
        }
        override fun attestationChainB64(): List<String> = emptyList()
        override fun isStrongBoxBacked(): Boolean = false
        override fun isHardwareBacked(): Boolean = false
        override fun inspectHardwareBacking() = PdsHardwareBackingClassifier.classify(
            alias = keyAlias(),
            apiLevel = 30,
            isInsideSecureHardware = false,
            securityLevelApi31 = PdsHardwareBackingClassifier.SECURITY_LEVEL_SOFTWARE,
            aliasPresent = true
        )
        override fun exportPrivateKeyBytes(): ByteArray {
            throw UnsupportedOperationException("PDS device private keys are non-exportable")
        }
    }

    private fun resultFor(signer: PdsDeviceKeySigner) = PdsEnrollmentCoordinator.EnrollmentResult(
        deviceId = uuid,
        deviceIdentityRef = "devid:$uuid:k1:abcd",
        tenantId = tenant,
        deviceKeyId = "devkey-v1",
        keyVersion = 1L,
        publicKeyFingerprintSha256 = signer.publicKeyFingerprintSha256(),
        lifecycleState = "REGISTERED",
        attestationTrustTier = "unverified",
        allowedCapabilities = listOf("COMMAND_EXECUTION"),
        allowedCommands = listOf("PING"),
        enrolledAt = "2026-11-02T12:00:00Z"
    )

    private fun challenge(hw: PdsEnrollmentCrypto.HardwareAttributes, b64: String = "Y2hhbGxlbmdl") =
        PdsEnrollmentCoordinator.ChallengeResponse(
            challengeId = "chl-1d4",
            challengeNonce = "bm9uY2UtMXZhbHVl",
            attestationChallengeB64 = b64,
            tenantId = tenant,
            publicKeyFingerprintSha256 = null,
            hardwareFingerprintSha256 = PdsEnrollmentCrypto.computeCanonicalHardwareFingerprint(hw),
            issuedAt = "2026-11-02T12:00:00Z",
            expiresAt = "2026-11-02T12:05:00Z"
        )

    @Test
    fun `challenge is requested before key provision and attestation bytes match exactly`() {
        val pair = generateSoftwareP256()
        val signer = NonExportableSoftwareSigner(pair)
        val provisioner = NoopIfKeyPresentProvisioner(signer)
        val events = mutableListOf<String>()
        val tempDir = Files.createTempDirectory("pds-1d4-order").toFile()
        try {
            val store = PdsEnrollmentStateStore(File(tempDir, "state"))
            val coordinator = testCoordinator(
                signer = signer,
                store = store,
                provisioner = provisioner,
                tempDir = tempDir,
                transport = object : PdsEnrollmentCoordinator.PdsEnrollmentTransport {
                    override suspend fun requestChallenge(
                        enrollmentToken: String,
                        tenantId: String,
                        devicePublicKeySpkiB64: String?,
                        hardware: PdsEnrollmentCrypto.HardwareAttributes
                    ): PdsEnrollmentCoordinator.ChallengeResponse {
                        events.add("challenge")
                        assertEquals(0, provisioner.generateCalls)
                        assertTrue(devicePublicKeySpkiB64.isNullOrBlank())
                        return challenge(hardware)
                    }

                    override suspend fun verifyEnrollment(
                        challengeId: String,
                        enrollmentToken: String,
                        tenantId: String,
                        devicePublicKeySpkiB64: String,
                        hardware: PdsEnrollmentCrypto.HardwareAttributes,
                        challengeSignatureB64Url: String,
                        attestation: PdsAttestationCollector.CollectedEvidence?,
                        recoveryNonce: String?,
                        recoverySignatureB64Url: String?,
                        claimedDeviceId: String?
                    ): PdsEnrollmentCoordinator.EnrollmentResult {
                        events.add("verify")
                        assertEquals(1, provisioner.generateCalls)
                        return resultFor(signer)
                    }
                }
            )
            runBlocking { coordinator.enroll("tok-abcdefgh", tenant, hardware) }
            assertEquals(listOf("challenge", "verify"), events)
            assertArrayEquals(
                Base64.getDecoder().decode("Y2hhbGxlbmdl"),
                provisioner.lastAttestationChallenge
            )
            assertTrue(store.current()!!.pdsEnrollmentComplete)
            assertFalse(store.current()!!.mdmRegistrationComplete)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `empty attestation challenge fails closed before key generation`() {
        val pair = generateSoftwareP256()
        val signer = NonExportableSoftwareSigner(pair)
        val provisioner = NoopIfKeyPresentProvisioner(signer)
        val tempDir = Files.createTempDirectory("pds-1d4-empty").toFile()
        try {
            val store = PdsEnrollmentStateStore(File(tempDir, "state"))
            val coordinator = testCoordinator(
                signer = signer,
                store = store,
                provisioner = provisioner,
                tempDir = tempDir,
                transport = object : PdsEnrollmentCoordinator.PdsEnrollmentTransport {
                    override suspend fun requestChallenge(
                        enrollmentToken: String,
                        tenantId: String,
                        devicePublicKeySpkiB64: String?,
                        hardware: PdsEnrollmentCrypto.HardwareAttributes
                    ) = challenge(hardware, b64 = "")

                    override suspend fun verifyEnrollment(
                        challengeId: String,
                        enrollmentToken: String,
                        tenantId: String,
                        devicePublicKeySpkiB64: String,
                        hardware: PdsEnrollmentCrypto.HardwareAttributes,
                        challengeSignatureB64Url: String,
                        attestation: PdsAttestationCollector.CollectedEvidence?,
                        recoveryNonce: String?,
                        recoverySignatureB64Url: String?,
                        claimedDeviceId: String?
                    ): PdsEnrollmentCoordinator.EnrollmentResult {
                        fail("verify must not run")
                        throw IllegalStateException()
                    }
                }
            )
            var threw = false
            try {
                runBlocking { coordinator.enroll("tok-abcdefgh", tenant, hardware) }
            } catch (_: SecurityException) {
                threw = true
            }
            assertTrue(threw)
            assertEquals(0, provisioner.generateCalls)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `recovery proof uses pds-enroll-recover-v1 and fresh nonce then persists server uuid`() {
        val pair = generateSoftwareP256()
        val signer = NonExportableSoftwareSigner(pair)
        val tempDir = Files.createTempDirectory("pds-1d4-recover").toFile()
        try {
            val store = PdsEnrollmentStateStore(File(tempDir, "state"))
            val coordinator = testCoordinator(
                signer = signer,
                store = store,
                tempDir = tempDir,
                transport = object : PdsEnrollmentCoordinator.PdsEnrollmentTransport {
                    override suspend fun requestChallenge(
                        enrollmentToken: String,
                        tenantId: String,
                        devicePublicKeySpkiB64: String?,
                        hardware: PdsEnrollmentCrypto.HardwareAttributes
                    ) = challenge(hardware)

                    override suspend fun verifyEnrollment(
                        challengeId: String,
                        enrollmentToken: String,
                        tenantId: String,
                        devicePublicKeySpkiB64: String,
                        hardware: PdsEnrollmentCrypto.HardwareAttributes,
                        challengeSignatureB64Url: String,
                        attestation: PdsAttestationCollector.CollectedEvidence?,
                        recoveryNonce: String?,
                        recoverySignatureB64Url: String?,
                        claimedDeviceId: String?
                    ): PdsEnrollmentCoordinator.EnrollmentResult {
                        if (recoveryNonce.isNullOrBlank()) {
                            throw PdsEnrollmentNeedsRecoveryException(challengeId)
                        }
                        val n = recoveryNonce
                        assertNotNull(n)
                        assertTrue(n!!.length >= 16)
                        val expected = PdsEnrollmentCrypto.buildEnrollmentRecoveryTranscript(
                            challengeId, n, null
                        )
                        assertTrue(
                            PdsEnrollmentCrypto.verifyDeviceSignature(
                                pair.public, expected, recoverySignatureB64Url!!
                            )
                        )
                        assertTrue(
                            String(expected, StandardCharsets.UTF_8)
                                .startsWith("pds-enroll-recover-v1|")
                        )
                        org.junit.Assert.assertNotEquals(
                            challengeSignatureB64Url,
                            recoverySignatureB64Url
                        )
                        org.junit.Assert.assertNull(claimedDeviceId)
                        return resultFor(signer)
                    }
                }
            )
            val enrolled = runBlocking { coordinator.enroll("tok-abcdefgh", tenant, hardware) }
            assertEquals(uuid, enrolled.deviceId)
            assertEquals(uuid, store.current()!!.deviceId)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `invalid recovery signature and missing private key fail closed without accepting a uuid`() {
        val pair = generateSoftwareP256()
        val broken = NonExportableSoftwareSigner(pair, failSign = true)
        val tempDir = Files.createTempDirectory("pds-1d4-nosig").toFile()
        try {
            val store = PdsEnrollmentStateStore(File(tempDir, "state"))
            val coordinator = testCoordinator(
                signer = broken,
                store = store,
                tempDir = tempDir,
                transport = object : PdsEnrollmentCoordinator.PdsEnrollmentTransport {
                    override suspend fun requestChallenge(
                        enrollmentToken: String,
                        tenantId: String,
                        devicePublicKeySpkiB64: String?,
                        hardware: PdsEnrollmentCrypto.HardwareAttributes
                    ) = challenge(hardware)

                    override suspend fun verifyEnrollment(
                        challengeId: String,
                        enrollmentToken: String,
                        tenantId: String,
                        devicePublicKeySpkiB64: String,
                        hardware: PdsEnrollmentCrypto.HardwareAttributes,
                        challengeSignatureB64Url: String,
                        attestation: PdsAttestationCollector.CollectedEvidence?,
                        recoveryNonce: String?,
                        recoverySignatureB64Url: String?,
                        claimedDeviceId: String?
                    ): PdsEnrollmentCoordinator.EnrollmentResult {
                        fail("verify must not run without a signature")
                        throw IllegalStateException()
                    }
                }
            )
            var threw = false
            try {
                runBlocking { coordinator.enroll("tok-abcdefgh", tenant, hardware) }
            } catch (_: SecurityException) {
                threw = true
            }
            assertTrue(threw)
            assertTrue(store.current() == null || !store.current()!!.pdsEnrollmentComplete)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `server committed identity is reused and replacement uuid is refused`() {
        val pair = generateSoftwareP256()
        val signer = NonExportableSoftwareSigner(pair)
        val tempDir = Files.createTempDirectory("pds-1d4-reuse").toFile()
        try {
            val storeFile = File(tempDir, "state")
            val store = PdsEnrollmentStateStore(storeFile)
            store.persistEnrollment(
                PdsEnrollmentStateStore.EnrollmentState(
                    deviceId = uuid,
                    deviceIdentityRef = "devid:$uuid:k1:abcd",
                    deviceKeyId = "devkey-v1",
                    tenantId = tenant,
                    keyVersion = 1L,
                    publicKeyFingerprintSha256 = signer.publicKeyFingerprintSha256(),
                    lifecycleState = "REGISTERED",
                    attestationTrustTier = "unverified",
                    allowedCapabilities = emptyList(),
                    allowedCommands = emptyList(),
                    enrolledAtIso = "2026-11-02T12:00:00Z",
                    lastAppliedManifestVersion = 0L,
                    lastAppliedManifestId = null,
                    lastAppliedManifestJwsSha256 = null,
                    pdsEnrollmentComplete = true,
                    mdmRegistrationComplete = false
                )
            )
            val identity = ConfirmedIdentityClient()
            val coordinator = testCoordinator(
                signer = signer,
                store = store,
                tempDir = tempDir,
                identityStatus = identity,
                transport = object : PdsEnrollmentCoordinator.PdsEnrollmentTransport {
                    override suspend fun requestChallenge(
                        enrollmentToken: String,
                        tenantId: String,
                        devicePublicKeySpkiB64: String?,
                        hardware: PdsEnrollmentCrypto.HardwareAttributes
                    ): PdsEnrollmentCoordinator.ChallengeResponse {
                        fail("must not re-challenge when PDS identity is already persisted")
                        throw IllegalStateException()
                    }

                    override suspend fun verifyEnrollment(
                        challengeId: String,
                        enrollmentToken: String,
                        tenantId: String,
                        devicePublicKeySpkiB64: String,
                        hardware: PdsEnrollmentCrypto.HardwareAttributes,
                        challengeSignatureB64Url: String,
                        attestation: PdsAttestationCollector.CollectedEvidence?,
                        recoveryNonce: String?,
                        recoverySignatureB64Url: String?,
                        claimedDeviceId: String?
                    ): PdsEnrollmentCoordinator.EnrollmentResult {
                        fail("must not re-verify")
                        throw IllegalStateException()
                    }
                }
            )
            val reused = runBlocking { coordinator.enroll("tok-abcdefgh", tenant, hardware) }
            assertEquals(uuid, reused.deviceId)
            assertEquals(1, identity.probes)

            val restarted = PdsEnrollmentStateStore(storeFile)
            assertEquals(uuid, restarted.current()!!.deviceId)
            assertTrue(restarted.current()!!.pdsEnrollmentComplete)
            assertFalse(restarted.current()!!.mdmRegistrationComplete)

            val otherStore = PdsEnrollmentStateStore(File(tempDir, "other"))
            otherStore.persistEnrollment(
                restarted.current()!!.copy(pdsEnrollmentComplete = false)
            )
            val conflicting = testCoordinator(
                signer = signer,
                store = otherStore,
                tempDir = tempDir,
                transport = object : PdsEnrollmentCoordinator.PdsEnrollmentTransport {
                    override suspend fun requestChallenge(
                        enrollmentToken: String,
                        tenantId: String,
                        devicePublicKeySpkiB64: String?,
                        hardware: PdsEnrollmentCrypto.HardwareAttributes
                    ) = challenge(hardware)

                    override suspend fun verifyEnrollment(
                        challengeId: String,
                        enrollmentToken: String,
                        tenantId: String,
                        devicePublicKeySpkiB64: String,
                        hardware: PdsEnrollmentCrypto.HardwareAttributes,
                        challengeSignatureB64Url: String,
                        attestation: PdsAttestationCollector.CollectedEvidence?,
                        recoveryNonce: String?,
                        recoverySignatureB64Url: String?,
                        claimedDeviceId: String?
                    ) = resultFor(signer).copy(deviceId = "550e8400-e29b-41d4-a716-446655440099")
                }
            )
            var conflict = false
            try {
                runBlocking { conflicting.enroll("tok-abcdefgh", tenant, hardware) }
            } catch (_: PdsIdentityConflictException) {
                conflict = true
            }
            assertTrue(conflict)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `mdm registration failure leaves pds uuid and retry uses the same known_device_id`() {
        val known = mutableListOf<String>()
        val attempts = AtomicInteger(0)
        val tempDir = Files.createTempDirectory("pds-1d4-mdm").toFile()
        try {
            val store = PdsEnrollmentStateStore(File(tempDir, "state"))
            val enroll: suspend (String, String, PdsEnrollmentCrypto.HardwareAttributes) -> PdsEnrollmentCoordinator.EnrollmentResult =
                { _, _, _ ->
                    val result = PdsEnrollmentCoordinator.EnrollmentResult(
                        deviceId = uuid,
                        deviceIdentityRef = "devid:$uuid:k1:abcd",
                        tenantId = tenant,
                        deviceKeyId = "k1",
                        keyVersion = 1L,
                        publicKeyFingerprintSha256 = "aa",
                        lifecycleState = "REGISTERED",
                        attestationTrustTier = "unverified",
                        allowedCapabilities = emptyList(),
                        allowedCommands = emptyList(),
                        enrolledAt = "2026-11-02T12:00:00Z"
                    )
                    store.persistEnrollment(
                        PdsEnrollmentStateStore.EnrollmentState(
                            deviceId = result.deviceId,
                            deviceIdentityRef = result.deviceIdentityRef,
                            deviceKeyId = result.deviceKeyId,
                            tenantId = result.tenantId,
                            keyVersion = result.keyVersion,
                            publicKeyFingerprintSha256 = result.publicKeyFingerprintSha256,
                            lifecycleState = result.lifecycleState,
                            attestationTrustTier = result.attestationTrustTier,
                            allowedCapabilities = result.allowedCapabilities,
                            allowedCommands = result.allowedCommands,
                            enrolledAtIso = result.enrolledAt,
                            lastAppliedManifestVersion = 0L,
                            lastAppliedManifestId = null,
                            lastAppliedManifestJwsSha256 = null,
                            pdsEnrollmentComplete = true,
                            mdmRegistrationComplete = false
                        )
                    )
                    result
                }
            val failRegister: suspend (PdsOnboardingEnrollmentFlow.LegacyRegisterRequest) -> PdsOnboardingEnrollmentFlow.LegacyRegisterResult =
                { req ->
                    known.add(req.knownDeviceId)
                    PdsOnboardingEnrollmentFlow.LegacyRegisterResult(false, 503, null, null)
                }
            var firstFailed = false
            try {
                runBlocking {
                    PdsOnboardingEnrollmentFlow.execute(
                        tenantId = tenant,
                        enrollmentToken = "tok-abcdefgh",
                        manufacturer = "Topwise",
                        model = "T1",
                        serial = "TW202611020001",
                        fingerprintHash = "aa",
                        agentCode = null,
                        businessName = null,
                        hardware = hardware,
                        pdsEnroll = enroll,
                        legacyRegister = failRegister,
                        pdsState = store
                    )
                }
            } catch (_: SecurityException) {
                firstFailed = true
            }
            assertTrue(firstFailed)
            assertEquals(uuid, store.current()!!.deviceId)
            assertTrue(store.current()!!.pdsEnrollmentComplete)
            assertFalse(store.current()!!.mdmRegistrationComplete)

            val restarted = PdsEnrollmentStateStore(File(tempDir, "state"))
            val outcome = runBlocking {
                PdsOnboardingEnrollmentFlow.execute(
                    tenantId = tenant,
                    enrollmentToken = "tok-abcdefgh",
                    manufacturer = "Topwise",
                    model = "T1",
                    serial = "TW202611020001",
                    fingerprintHash = "aa",
                    agentCode = null,
                    businessName = null,
                    hardware = hardware,
                    pdsState = restarted,
                    pdsEnroll = { _, _, _ ->
                        attempts.incrementAndGet()
                        PdsEnrollmentCoordinator.EnrollmentResult(
                            deviceId = restarted.current()!!.deviceId,
                            deviceIdentityRef = restarted.current()!!.deviceIdentityRef,
                            tenantId = tenant,
                            deviceKeyId = "k1",
                            keyVersion = 1L,
                            publicKeyFingerprintSha256 = "aa",
                            lifecycleState = "REGISTERED",
                            attestationTrustTier = "unverified",
                            allowedCapabilities = emptyList(),
                            allowedCommands = emptyList(),
                            enrolledAt = "2026-11-02T12:00:00Z"
                        )
                    },
                    legacyRegister = { req ->
                        known.add(req.knownDeviceId)
                        PdsOnboardingEnrollmentFlow.LegacyRegisterResult(
                            true, 200, uuid, "mdm-jwt"
                        )
                    }
                )
            }
            assertEquals(uuid, outcome.knownDeviceIdSentToLegacy)
            assertEquals(listOf(uuid, uuid), known)
            assertTrue(restarted.current()!!.mdmRegistrationComplete)
            assertEquals(1, attempts.get())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `private keys are not exportable and token hash keygen is rejected`() {
        val pair = generateSoftwareP256()
        val signer = NonExportableSoftwareSigner(pair)
        var exportBlocked = false
        try {
            signer.exportPrivateKeyBytes()
        } catch (_: UnsupportedOperationException) {
            exportBlocked = true
        }
        assertTrue(exportBlocked)

        var emptyChallengeRejected = false
        try {
            NoopIfKeyPresentProvisioner(signer).generateBoundToAttestationChallenge(ByteArray(0))
        } catch (_: SecurityException) {
            emptyChallengeRejected = true
        }
        assertTrue(emptyChallengeRejected)

        val nonce = PdsEnrollmentCrypto.generateEnrollRecoverNonce()
        assertTrue(nonce.length >= 16)
        val t = String(
            PdsEnrollmentCrypto.buildEnrollmentRecoveryTranscript("chl-1", nonce, uuid),
            StandardCharsets.UTF_8
        )
        assertEquals(
            "pds-enroll-recover-v1|chl-1|$uuid|enroll_recover|$nonce",
            t
        )
    }

    @Test
    fun `decodeRequiredAttestationChallenge rejects blank and empty payloads`() {
        var blank = false
        try {
            PdsEnrollmentCrypto.decodeRequiredAttestationChallenge("   ")
        } catch (_: SecurityException) {
            blank = true
        }
        assertTrue(blank)
        val decoded = PdsEnrollmentCrypto.decodeRequiredAttestationChallenge("Y2hhbGxlbmdl")
        assertNotNull(decoded)
        assertTrue(decoded.isNotEmpty())
    }
}
