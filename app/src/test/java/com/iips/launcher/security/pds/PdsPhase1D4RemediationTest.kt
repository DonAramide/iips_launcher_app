package com.iips.launcher.security.pds

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

/**
 * JVM fakes for 1D.4 remediation findings. Not hardware Keystore or Quasar
 * integration tests.
 */
class PdsPhase1D4RemediationTest {

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

    private class NonExportableSoftwareSigner(private val pair: KeyPair) : PdsDeviceKeySigner {
        override fun keyAlias() = "pds_device_key_v1"
        override fun publicKeySpkiB64(): String =
            Base64.getEncoder().encodeToString(pair.public.encoded)
        override fun publicKeyFingerprintSha256(): String =
            PdsEnrollmentCrypto.publicKeyFingerprintSha256(pair.public.encoded)
        override fun signPayload(payloadBytes: ByteArray): String {
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

    private fun challenge(
        hw: PdsEnrollmentCrypto.HardwareAttributes,
        id: String = "chl-1d4",
        b64: String = "Y2hhbGxlbmdl",
        expiresAt: String = "2099-11-02T12:05:00Z"
    ) = PdsEnrollmentCoordinator.ChallengeResponse(
        challengeId = id,
        challengeNonce = "bm9uY2UtMXZhbHVl",
        attestationChallengeB64 = b64,
        tenantId = tenant,
        publicKeyFingerprintSha256 = null,
        hardwareFingerprintSha256 = PdsEnrollmentCrypto.computeCanonicalHardwareFingerprint(hw),
        issuedAt = "2026-11-02T12:00:00Z",
        expiresAt = expiresAt
    )

    private fun persistComplete(store: PdsEnrollmentStateStore, signer: PdsDeviceKeySigner) {
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
    }

    @Test
    fun `existing alias with missing binding fails closed`() {
        val bytes = Base64.getDecoder().decode("Y2hhbGxlbmdl")
        val hash = PdsEnrollmentCrypto.sha256Hex(bytes)
        PdsKeystoreBindingPolicy.decide(false, null, hash)
        var missing = false
        try {
            PdsKeystoreBindingPolicy.decide(true, null, hash)
        } catch (_: SecurityException) {
            missing = true
        }
        assertTrue(missing)
        var blank = false
        try {
            PdsKeystoreBindingPolicy.decide(true, "   ", hash)
        } catch (_: SecurityException) {
            blank = true
        }
        assertTrue(blank)
    }

    @Test
    fun `existing alias with mismatched or corrupted binding fails closed`() {
        val bytes = Base64.getDecoder().decode("Y2hhbGxlbmdl")
        val hash = PdsEnrollmentCrypto.sha256Hex(bytes)
        assertEquals(
            PdsKeystoreBindingPolicy.Decision.REUSE,
            PdsKeystoreBindingPolicy.decide(true, hash, hash)
        )
        var mismatch = false
        try {
            PdsKeystoreBindingPolicy.decide(true, "00".repeat(32), hash)
        } catch (_: SecurityException) {
            mismatch = true
        }
        assertTrue(mismatch)

        val tempDir = Files.createTempDirectory("pds-bind-corrupt").toFile()
        try {
            val binding = PdsAttestationBindingStore(File(tempDir, "bind"))
            File(tempDir, "bind").writeText("not-a-valid-matching-hash")
            val provisioner = BindingAwareFakeProvisioner(binding, aliasExists = true)
            var threw = false
            try {
                provisioner.generateBoundToAttestationChallenge(bytes)
            } catch (_: SecurityException) {
                threw = true
            }
            assertTrue(threw)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `interrupted enrollment resumes the same valid challenge`() {
        val pair = generateSoftwareP256()
        val signer = NonExportableSoftwareSigner(pair)
        val tempDir = Files.createTempDirectory("pds-resume").toFile()
        try {
            val store = PdsEnrollmentStateStore(File(tempDir, "state"))
            val pending = PdsPendingEnrollmentStore(File(tempDir, "pending"))
            val binding = PdsAttestationBindingStore(File(tempDir, "bind"))
            val provisioner = BindingAwareFakeProvisioner(binding)
            val challenges = AtomicInteger(0)
            val verifies = AtomicInteger(0)
            val transport = object : PdsEnrollmentCoordinator.PdsEnrollmentTransport {
                override suspend fun requestChallenge(
                    enrollmentToken: String,
                    tenantId: String,
                    devicePublicKeySpkiB64: String?,
                    hardware: PdsEnrollmentCrypto.HardwareAttributes
                ): PdsEnrollmentCoordinator.ChallengeResponse {
                    challenges.incrementAndGet()
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
                    if (verifies.incrementAndGet() == 1) {
                        throw SecurityException("simulated interrupt after keygen")
                    }
                    return resultFor(signer)
                }
            }
            val coordinator = testCoordinator(
                signer = signer,
                store = store,
                transport = transport,
                tempDir = tempDir,
                provisioner = provisioner,
                pendingStore = pending
            )
            var interrupted = false
            try {
                runBlocking { coordinator.enroll("tok-abcdefgh", tenant, hardware) }
            } catch (_: SecurityException) {
                interrupted = true
            }
            assertTrue(interrupted)
            assertEquals(1, challenges.get())
            assertEquals("chl-1d4", pending.current()!!.challengeId)
            assertTrue(provisioner.aliasExists)

            val restartedPending = PdsPendingEnrollmentStore(File(tempDir, "pending"))
            val restarted = testCoordinator(
                signer = signer,
                store = PdsEnrollmentStateStore(File(tempDir, "state")),
                transport = transport,
                tempDir = tempDir,
                provisioner = provisioner,
                pendingStore = restartedPending
            )
            val enrolled = runBlocking { restarted.enroll("tok-abcdefgh", tenant, hardware) }
            assertEquals(uuid, enrolled.deviceId)
            assertEquals(1, challenges.get())
            assertEquals(null, restartedPending.current())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `expired challenge with existing key requires authorized replacement`() {
        val pair = generateSoftwareP256()
        val signer = NonExportableSoftwareSigner(pair)
        val tempDir = Files.createTempDirectory("pds-expired").toFile()
        try {
            val pending = PdsPendingEnrollmentStore(File(tempDir, "pending"))
            pending.persist(
                PdsPendingEnrollmentStore.PendingEnrollment(
                    challengeId = "chl-old",
                    challengeNonce = "bm9uY2U",
                    attestationChallengeB64 = "Y2hhbGxlbmdl",
                    attestationChallengeHashSha256 = PdsEnrollmentCrypto.sha256Hex(
                        Base64.getDecoder().decode("Y2hhbGxlbmdl")
                    ),
                    tenantId = tenant,
                    publicKeyFingerprintSha256 = null,
                    hardwareFingerprintSha256 = PdsEnrollmentCrypto.computeCanonicalHardwareFingerprint(hardware),
                    issuedAt = "2020-01-01T00:00:00Z",
                    expiresAt = "2020-01-01T00:05:00Z"
                )
            )
            val binding = PdsAttestationBindingStore(File(tempDir, "bind"))
            val provisioner = BindingAwareFakeProvisioner(binding, aliasExists = true)
            binding.writeHash(
                PdsEnrollmentCrypto.sha256Hex(Base64.getDecoder().decode("Y2hhbGxlbmdl"))
            )
            var challengeCalls = 0
            val coordinator = testCoordinator(
                signer = signer,
                store = PdsEnrollmentStateStore(File(tempDir, "state")),
                provisioner = provisioner,
                pendingStore = pending,
                tempDir = tempDir,
                nowEpochMilli = { System.currentTimeMillis() },
                transport = object : PdsEnrollmentCoordinator.PdsEnrollmentTransport {
                    override suspend fun requestChallenge(
                        enrollmentToken: String,
                        tenantId: String,
                        devicePublicKeySpkiB64: String?,
                        hardware: PdsEnrollmentCrypto.HardwareAttributes
                    ): PdsEnrollmentCoordinator.ChallengeResponse {
                        challengeCalls += 1
                        fail("must not mint a second challenge while an expired key exists")
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
                        fail("verify must not run")
                        throw IllegalStateException()
                    }
                }
            )
            var rotation = false
            try {
                runBlocking { coordinator.enroll("tok-abcdefgh", tenant, hardware) }
            } catch (_: PdsAuthorizedRotationRequiredException) {
                rotation = true
            }
            assertTrue(rotation)
            assertEquals(0, challengeCalls)
            assertTrue(provisioner.keyExists())
            assertEquals("chl-old", pending.current()!!.challengeId)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `recovery keeps enroll signature out of recovery fields`() {
        val pair = generateSoftwareP256()
        val signer = NonExportableSoftwareSigner(pair)
        val tempDir = Files.createTempDirectory("pds-sep").toFile()
        try {
            val store = PdsEnrollmentStateStore(File(tempDir, "state"))
            var sawRecovery = false
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
                        sawRecovery = true
                        val enrollTranscript = PdsEnrollmentCrypto.buildEnrollmentChallengeTranscript(
                            PdsEnrollmentCrypto.EnrollmentTranscriptParams(
                                challengeId = challengeId,
                                challengeNonce = "bm9uY2UtMXZhbHVl",
                                enrollmentToken = enrollmentToken,
                                tenantId = tenantId,
                                publicKeyFingerprintSha256 = signer.publicKeyFingerprintSha256(),
                                hardwareFingerprintSha256 =
                                    PdsEnrollmentCrypto.computeCanonicalHardwareFingerprint(hardware)
                            )
                        )
                        val recoveryTranscript = PdsEnrollmentCrypto.buildEnrollmentRecoveryTranscript(
                            challengeId, recoveryNonce!!, null
                        )
                        assertTrue(
                            PdsEnrollmentCrypto.verifyDeviceSignature(
                                pair.public, enrollTranscript, challengeSignatureB64Url
                            )
                        )
                        assertTrue(
                            PdsEnrollmentCrypto.verifyDeviceSignature(
                                pair.public, recoveryTranscript, recoverySignatureB64Url!!
                            )
                        )
                        assertNotEquals(challengeSignatureB64Url, recoverySignatureB64Url)
                        assertFalse(
                            String(enrollTranscript, StandardCharsets.UTF_8)
                                .startsWith("pds-enroll-recover-v1|")
                        )
                        return resultFor(signer)
                    }
                }
            )
            runBlocking { coordinator.enroll("tok-abcdefgh", tenant, hardware) }
            assertTrue(sawRecovery)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `only enrollment_already_completed enters recovery`() {
        val recoveryCodes = listOf(PdsEnrollmentHttpErrors.ENROLLMENT_ALREADY_COMPLETED)
        val blocked = listOf(
            PdsEnrollmentHttpErrors.IDENTITY_MISMATCH,
            PdsEnrollmentHttpErrors.DEVICE_OWNED_BY_OTHER_TENANT,
            PdsEnrollmentHttpErrors.DUPLICATE_DEVICE_ENROLLMENT,
            PdsEnrollmentHttpErrors.ENROLLMENT_CHALLENGE_REPLAYED,
            null
        )
        recoveryCodes.forEach { code ->
            assertTrue(PdsEnrollmentHttpErrors.shouldEnterRecovery(409, code))
        }
        blocked.forEach { code ->
            assertFalse(PdsEnrollmentHttpErrors.shouldEnterRecovery(409, code))
        }
        assertFalse(PdsEnrollmentHttpErrors.shouldEnterRecovery(403, PdsEnrollmentHttpErrors.ENROLLMENT_ALREADY_COMPLETED))
        assertEquals(
            PdsEnrollmentHttpErrors.IDENTITY_MISMATCH,
            PdsEnrollmentHttpErrors.parseErrorCode("""{"error_code":"identity_mismatch"}""")
        )

        val pair = generateSoftwareP256()
        val signer = NonExportableSoftwareSigner(pair)
        for (code in listOf(
            PdsEnrollmentHttpErrors.IDENTITY_MISMATCH,
            PdsEnrollmentHttpErrors.DEVICE_OWNED_BY_OTHER_TENANT,
            PdsEnrollmentHttpErrors.DUPLICATE_DEVICE_ENROLLMENT,
            PdsEnrollmentHttpErrors.ENROLLMENT_CHALLENGE_REPLAYED
        )) {
            val tempDir = Files.createTempDirectory("pds-409-$code").toFile()
            try {
                val coordinator = testCoordinator(
                    signer = signer,
                    store = PdsEnrollmentStateStore(File(tempDir, "state")),
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
                            if (recoveryNonce != null) {
                                fail("$code must not enter recovery")
                            }
                            throw PdsEnrollmentConflictException(code)
                        }
                    }
                )
                var conflict = false
                try {
                    runBlocking { coordinator.enroll("tok-abcdefgh", tenant, hardware) }
                } catch (e: PdsEnrollmentConflictException) {
                    conflict = e.errorCode == code
                }
                assertTrue(conflict)
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    @Test
    fun `authenticated server identity loss requires authorized reenrollment`() {
        val pair = generateSoftwareP256()
        val signer = NonExportableSoftwareSigner(pair)
        val tempDir = Files.createTempDirectory("pds-missing").toFile()
        try {
            val store = PdsEnrollmentStateStore(File(tempDir, "state"))
            persistComplete(store, signer)
            var challenges = 0
            val coordinator = testCoordinator(
                signer = signer,
                store = store,
                tempDir = tempDir,
                identityStatus = ScriptedIdentityClient(
                    PdsDeviceIdentityStatusKind.DEFINITIVELY_MISSING,
                    PdsEnrollmentHttpErrors.DEVICE_NOT_REGISTERED
                ),
                transport = object : PdsEnrollmentCoordinator.PdsEnrollmentTransport {
                    override suspend fun requestChallenge(
                        enrollmentToken: String,
                        tenantId: String,
                        devicePublicKeySpkiB64: String?,
                        hardware: PdsEnrollmentCrypto.HardwareAttributes
                    ): PdsEnrollmentCoordinator.ChallengeResponse {
                        challenges += 1
                        fail("must not create a replacement identity")
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
                        fail("must not verify a replacement")
                        throw IllegalStateException()
                    }
                }
            )
            var missing = false
            try {
                runBlocking { coordinator.enroll("tok-abcdefgh", tenant, hardware) }
            } catch (_: PdsServerIdentityMissingException) {
                missing = true
            }
            assertTrue(missing)
            assertEquals(0, challenges)
            assertEquals(uuid, store.current()!!.deviceId)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `network failure does not treat identity as deleted`() {
        val pair = generateSoftwareP256()
        val signer = NonExportableSoftwareSigner(pair)
        val tempDir = Files.createTempDirectory("pds-net").toFile()
        try {
            val store = PdsEnrollmentStateStore(File(tempDir, "state"))
            persistComplete(store, signer)
            var challenges = 0
            val coordinator = testCoordinator(
                signer = signer,
                store = store,
                tempDir = tempDir,
                identityStatus = ScriptedIdentityClient(
                    PdsDeviceIdentityStatusKind.INDETERMINATE,
                    "network_failure"
                ),
                transport = object : PdsEnrollmentCoordinator.PdsEnrollmentTransport {
                    override suspend fun requestChallenge(
                        enrollmentToken: String,
                        tenantId: String,
                        devicePublicKeySpkiB64: String?,
                        hardware: PdsEnrollmentCrypto.HardwareAttributes
                    ): PdsEnrollmentCoordinator.ChallengeResponse {
                        challenges += 1
                        fail("network failure must not start a new enroll")
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
                        fail("unreachable")
                        throw IllegalStateException()
                    }
                }
            )
            val reused = runBlocking { coordinator.enroll("tok-abcdefgh", tenant, hardware) }
            assertEquals(uuid, reused.deviceId)
            assertEquals(0, challenges)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `truncated local state fails closed and is quarantined`() {
        val tempDir = Files.createTempDirectory("pds-trunc").toFile()
        try {
            val file = File(tempDir, "state")
            file.writeText("device_id=550e8400-e29b-41d4-a716-446655440001\ntenant_id=")
            var integrity = false
            try {
                PdsEnrollmentStateStore(file).current()
            } catch (_: PdsEnrollmentStateIntegrityException) {
                integrity = true
            }
            assertTrue(integrity)
            assertTrue(file.exists())
            val quarantined = tempDir.listFiles()?.any { it.name.startsWith("state.quarantine.") }
            assertTrue(quarantined == true)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `missing mandatory local state fields fail closed`() {
        val tempDir = Files.createTempDirectory("pds-missing-fields").toFile()
        try {
            val file = File(tempDir, "state")
            file.writeText(
                """
                device_id=550e8400-e29b-41d4-a716-446655440001
                tenant_id=$tenant
                lifecycle_state=ACTIVE
                """.trimIndent()
            )
            var integrity = false
            try {
                PdsEnrollmentStateStore(file).current()
            } catch (e: PdsEnrollmentStateIntegrityException) {
                integrity = e.message!!.contains("device_identity_ref")
            }
            assertTrue(integrity)
            assertTrue(file.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `two concurrent enrollment calls are single flight`() {
        val pair = generateSoftwareP256()
        val signer = NonExportableSoftwareSigner(pair)
        val tempDir = Files.createTempDirectory("pds-flight").toFile()
        try {
            val store = PdsEnrollmentStateStore(File(tempDir, "state"))
            val inFlight = AtomicInteger(0)
            val maxInFlight = AtomicInteger(0)
            val challenges = AtomicInteger(0)
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
                    ): PdsEnrollmentCoordinator.ChallengeResponse {
                        val n = inFlight.incrementAndGet()
                        maxInFlight.updateAndGet { cur -> maxOf(cur, n) }
                        challenges.incrementAndGet()
                        delay(80)
                        inFlight.decrementAndGet()
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
                    ) = resultFor(signer)
                }
            )
            runBlocking {
                val first = async { coordinator.enroll("tok-abcdefgh", tenant, hardware) }
                val second = async { coordinator.enroll("tok-abcdefgh", tenant, hardware) }
                assertEquals(uuid, first.await().deviceId)
                assertEquals(uuid, second.await().deviceId)
            }
            assertEquals(1, maxInFlight.get())
            assertEquals(1, challenges.get())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `production provisioner wiring rejects test doubles`() {
        val pair = generateSoftwareP256()
        val signer = NonExportableSoftwareSigner(pair)
        val noop = NoopIfKeyPresentProvisioner(signer)
        var rejected = false
        try {
            PdsProductionEnrollmentWiring.requireProductionProvisioner(noop)
        } catch (_: SecurityException) {
            rejected = true
        }
        assertTrue(rejected)
        var fakeRejected = false
        try {
            PdsProductionEnrollmentWiring.requireProductionProvisioner(
                object : PdsDeviceKeyProvisioner {
                    override fun generateBoundToAttestationChallenge(attestationChallengeBytes: ByteArray) = Unit
                    override fun keyExists(): Boolean = false
                }
            )
        } catch (_: SecurityException) {
            fakeRejected = true
        }
        assertTrue(fakeRejected)
        assertEquals(
            "PdsAndroidKeystoreBoundProvisioner",
            PdsAndroidKeystoreBoundProvisioner::class.java.simpleName
        )
    }
}
