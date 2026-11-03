package com.iips.launcher.security.pds

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

class PdsPhase1CEnrollmentAndManifestTest {

    @Suppress("UNCHECKED_CAST")
    private fun loadSuite(): Map<String, Any?> {
        val stream = javaClass.classLoader?.getResourceAsStream(
            "pds-phase-1c-enrollment-manifest-vectors.v1.json"
        )
        val text = if (stream != null) {
            stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } else {
            File("src/test/resources/pds-phase-1c-enrollment-manifest-vectors.v1.json")
                .readText(StandardCharsets.UTF_8)
        }
        return PdsCanonicalCommandEnvelope.parseJsonRejectingDuplicateKeys(text) as Map<String, Any?>
    }

    private fun generateSoftwareP256(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    private class NonExportableSoftwareSigner(
        private val pair: KeyPair
    ) : PdsDeviceKeySigner {
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
            apiLevel = 26,
            isInsideSecureHardware = false,
            securityLevelApi31 = PdsHardwareBackingClassifier.SECURITY_LEVEL_SOFTWARE,
            aliasPresent = true
        )
        override fun exportPrivateKeyBytes(): ByteArray {
            throw UnsupportedOperationException(
                "PDS device private keys are non-exportable (SPEC-03 §3)"
            )
        }
    }

    @Test
    fun `matches shared enrollment transcripts key rotation request signatures and 12 manifest vectors`() {
        val suite = loadSuite()
        val evalIso = suite["evaluationTimeIso"] as String
        val evalEpochSec = PdsCanonicalCommandEnvelope.parseUtcIsoSeconds(evalIso, "evaluationTimeIso")

        @Suppress("UNCHECKED_CAST")
        val enroll = suite["enrollmentSample"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val hardware = enroll["hardware"] as Map<String, Any?>
        val hw = PdsEnrollmentCrypto.HardwareAttributes(
            manufacturer = hardware["manufacturer"] as String,
            model = hardware["model"] as String,
            hardwareId = hardware["hardware_id"] as String,
            serialNumber = hardware["serial_number"] as String?,
            androidVersion = hardware["android_version"] as String,
            sdkInt = hardware["sdk_int"] as Long,
            buildFingerprint = hardware["build_fingerprint"] as String,
            pdsVersion = hardware["pds_version"] as String,
            vendor = hardware["vendor"] as String?
        )
        val hwFp = PdsEnrollmentCrypto.computeCanonicalHardwareFingerprint(hw)
        assertEquals(enroll["hardwareFingerprintSha256"] as String, hwFp)

        val transcript = PdsEnrollmentCrypto.buildEnrollmentChallengeTranscript(
            PdsEnrollmentCrypto.EnrollmentTranscriptParams(
                challengeId = enroll["challengeId"] as String,
                challengeNonce = enroll["challengeNonce"] as String,
                enrollmentToken = enroll["enrollmentToken"] as String,
                tenantId = enroll["tenantId"] as String,
                publicKeyFingerprintSha256 = enroll["publicKeyFingerprintSha256"] as String,
                hardwareFingerprintSha256 = hwFp
            )
        )
        assertEquals(
            enroll["canonicalTranscriptUtf8"] as String,
            String(transcript, StandardCharsets.UTF_8)
        )

        val enrollPub = PdsCommandVerifier.decodeEcPublicKey(enroll["devicePublicKeySpkiB64"] as String)
        assertTrue(
            PdsEnrollmentCrypto.verifyDeviceSignature(
                enrollPub,
                transcript,
                enroll["challengeSignatureB64Url"] as String
            )
        )

        @Suppress("UNCHECKED_CAST")
        val rot = suite["keyRotationSample"] as Map<String, Any?>
        val rotTranscript = PdsEnrollmentCrypto.buildKeyRotationTranscript(
            PdsEnrollmentCrypto.KeyRotationTranscriptParams(
                deviceId = rot["deviceId"] as String,
                tenantId = rot["tenantId"] as String,
                currentDeviceKeyId = rot["currentDeviceKeyId"] as String,
                newPublicKeyFingerprintSha256 = rot["newPublicKeyFingerprintSha256"] as String,
                timestampSec = rot["timestampSec"] as Long,
                nonce = rot["nonce"] as String
            )
        )
        assertEquals(
            rot["canonicalTranscriptUtf8"] as String,
            String(rotTranscript, StandardCharsets.UTF_8)
        )
        val currentPub = PdsCommandVerifier.decodeEcPublicKey(rot["currentPublicKeySpkiB64"] as String)
        val newPub = PdsCommandVerifier.decodeEcPublicKey(rot["newPublicKeySpkiB64"] as String)
        assertTrue(
            PdsEnrollmentCrypto.verifyDeviceSignature(
                currentPub,
                rotTranscript,
                rot["currentKeySignatureB64Url"] as String
            )
        )
        assertTrue(
            PdsEnrollmentCrypto.verifyDeviceSignature(
                newPub,
                rotTranscript,
                rot["newKeySignatureB64Url"] as String
            )
        )

        @Suppress("UNCHECKED_CAST")
        val req = suite["requestSignatureSample"] as Map<String, Any?>
        val reqBytes = PdsEnrollmentCrypto.buildDeviceRequestSigningBytes(
            PdsEnrollmentCrypto.DeviceRequestSigningParams(
                method = req["method"] as String,
                path = req["path"] as String,
                canonicalQuery = req["canonicalQuery"] as String,
                deviceId = req["deviceId"] as String,
                keyId = req["deviceKeyId"] as String,
                timestampSec = req["timestampSec"] as Long,
                nonce = req["nonce"] as String
            )
        )
        val canonical = String(reqBytes, StandardCharsets.UTF_8)
        assertTrue(canonical.startsWith("pds-req-v1\n"))
        if (req["canonicalUtf8"] != null) {
            assertEquals(req["canonicalUtf8"] as String, canonical)
        }
        val reqPub = PdsCommandVerifier.decodeEcPublicKey(req["publicKeySpkiB64"] as String)
        val header = req["signatureHeader"] as String
        val sigPart = header.split(",").first { it.trim().startsWith("sig=") }.substringAfter("=")
        assertTrue(PdsEnrollmentCrypto.verifyDeviceSignature(reqPub, reqBytes, sigPart))

        @Suppress("UNCHECKED_CAST")
        val keySetDist = suite["keySetDistribution"] as Map<String, Any?>
        val verifier = PdsManifestVerifier()
        verifier.applySignedKeySetJws(keySetDist["keyset_jws"] as String, evalEpochSec)

        @Suppress("UNCHECKED_CAST")
        val vectors = suite["manifestVectors"] as List<Map<String, Any?>>
        assertEquals(12, vectors.size)
        for (vec in vectors) {
            val id = vec["id"] as String
            val expectedValid = vec["expectedValid"] as Boolean
            val expectedError = vec["expectedErrorCode"] as? String
            val outcome = verifier.verifyManifestJws(
                vec["manifestJws"] as String,
                PdsManifestVerifier.VerificationOptions(
                    expectedDeviceId = vec["expectedDeviceId"] as String,
                    expectedDeviceIdentityRef = vec["expectedDeviceIdentityRef"] as String,
                    expectedTenantId = vec["expectedTenantId"] as String,
                    expectedVendor = vec["expectedVendor"] as String,
                    lastAcceptedManifestVersion = vec["lastAcceptedManifestVersion"] as Long,
                    nowEpochSeconds = PdsCanonicalCommandEnvelope.parseUtcIsoSeconds(
                        vec["verificationNowIso"] as String,
                        "now"
                    )
                )
            )
            assertEquals(
                "Vector $id validity mismatch (${outcome.failureReason})",
                expectedValid,
                outcome.valid
            )
            assertEquals(
                "Vector $id error code mismatch (${outcome.failureReason})",
                expectedError,
                outcome.failureCode?.name
            )
        }
    }

    @Test
    fun `software device keys cannot be exported and enrollment state survives restart`() {
        val pair = generateSoftwareP256()
        val signer = NonExportableSoftwareSigner(pair)
        var threw = false
        try {
            signer.exportPrivateKeyBytes()
        } catch (_: UnsupportedOperationException) {
            threw = true
        }
        assertTrue(threw)
        assertFalse(signer.isHardwareBacked())
        assertTrue(signer.publicKeySpkiB64().isNotBlank())

        val payload = "pds-enroll-v1|challenge".toByteArray(StandardCharsets.UTF_8)
        val sig = signer.signPayload(payload)
        assertTrue(
            PdsEnrollmentCrypto.verifyDeviceSignature(
                pair.public,
                payload,
                sig
            )
        )

        val tempDir = Files.createTempDirectory("pds-enroll-state").toFile()
        try {
            val storeFile = File(tempDir, "pds_enrollment.state")
            val store = PdsEnrollmentStateStore(storeFile)
            store.persistEnrollment(
                PdsEnrollmentStateStore.EnrollmentState(
                    deviceId = "550e8400-e29b-41d4-a716-446655440001",
                    deviceIdentityRef = "devid:550e8400-e29b-41d4-a716-446655440001:k1:abcd",
                    deviceKeyId = "devkey-550e8400-v1",
                    tenantId = "11111111-2222-3333-4444-555555555555",
                    keyVersion = 1L,
                    publicKeyFingerprintSha256 = signer.publicKeyFingerprintSha256(),
                    lifecycleState = "ACTIVE",
                    attestationTrustTier = "hardware_relaxed",
                    allowedCapabilities = listOf("COMMAND_EXECUTION", "EE_BINDING"),
                    allowedCommands = listOf("PING", "LOCK"),
                    enrolledAtIso = "2026-11-02T12:00:00Z",
                    lastAppliedManifestVersion = 2L,
                    lastAppliedManifestId = "mfs-doc-pos-01",
                    lastAppliedManifestJwsSha256 = "abc"
                )
            )
            val restarted = PdsEnrollmentStateStore(storeFile)
            val recovered = restarted.current()
            assertNotNull(recovered)
            assertEquals("550e8400-e29b-41d4-a716-446655440001", recovered!!.deviceId)
            assertEquals("ACTIVE", recovered.lifecycleState)
            assertEquals(2L, recovered.lastAppliedManifestVersion)
            assertTrue(recovered.canAuthenticate())

            var rollbackThrew = false
            try {
                restarted.recordAppliedManifest("old", 1L, "def")
            } catch (_: SecurityException) {
                rollbackThrew = true
            }
            assertTrue(rollbackThrew)
            assertEquals(2L, restarted.current()!!.lastAppliedManifestVersion)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `coordinator enrolls via challenge response and rejects ANDROID_ID serials and prohibited manifests`() {
        val pair = generateSoftwareP256()
        val signer = NonExportableSoftwareSigner(pair)
        val tempDir = Files.createTempDirectory("pds-enroll-coord").toFile()
        try {
            val store = PdsEnrollmentStateStore(File(tempDir, "state"))
            val hw = PdsEnrollmentCrypto.HardwareAttributes(
                manufacturer = "Topwise",
                model = "T1",
                hardwareId = "tw-t1-board-01",
                serialNumber = "9774d56d682e549c",
                androidVersion = "11",
                sdkInt = 30L,
                buildFingerprint = "Topwise/T1/T1:11/RP1A.200720.011/20260101:user/release-keys",
                pdsVersion = "1.0.0",
                vendor = "topwise"
            )
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
                        fail("challenge must not be requested for ANDROID_ID serial")
                        throw IllegalStateException("unreachable")
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
                        fail("verify must not run for ANDROID_ID serial")
                        throw IllegalStateException("unreachable")
                    }
                }
            )
            var androidIdRejected = false
            try {
                runBlocking { coordinator.enroll("tok", "tenant-a", hw) }
            } catch (_: SecurityException) {
                androidIdRejected = true
            }
            assertTrue(androidIdRejected)

            val okHw = hw.copy(serialNumber = "TW202611020001")
            var capturedSig: String? = null
            var challengeSpki: String? = "unset"
            val provisioner = NoopIfKeyPresentProvisioner(signer)
            val okCoordinator = testCoordinator(
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
                        challengeSpki = devicePublicKeySpkiB64
                        assertEquals(0, provisioner.generateCalls)
                        val hwFp = PdsEnrollmentCrypto.computeCanonicalHardwareFingerprint(hardware)
                        return PdsEnrollmentCoordinator.ChallengeResponse(
                            challengeId = "chl-test-1",
                            challengeNonce = "bm9uY2U",
                            attestationChallengeB64 = "Y2hhbGxlbmdl",
                            tenantId = tenantId,
                            publicKeyFingerprintSha256 = null,
                            hardwareFingerprintSha256 = hwFp,
                            issuedAt = "2026-11-02T12:00:00Z",
                            expiresAt = "2026-11-02T12:05:00Z"
                        )
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
                        capturedSig = challengeSignatureB64Url
                        assertEquals(1, provisioner.generateCalls)
                        assertEquals("unsupported", attestation?.securityLevel?.name?.lowercase())
                        return PdsEnrollmentCoordinator.EnrollmentResult(
                            deviceId = "550e8400-e29b-41d4-a716-446655440099",
                            deviceIdentityRef = "devid:550e8400-e29b-41d4-a716-446655440099:k1:ffff",
                            tenantId = tenantId,
                            deviceKeyId = "devkey-dev-1-v1",
                            keyVersion = 1L,
                            publicKeyFingerprintSha256 = signer.publicKeyFingerprintSha256(),
                            lifecycleState = "REGISTERED",
                            attestationTrustTier = "software",
                            allowedCapabilities = listOf("COMMAND_EXECUTION"),
                            allowedCommands = listOf("PING"),
                            enrolledAt = "2026-11-02T12:00:00Z"
                        )
                    }
                }
            )
            val enrolled = runBlocking { okCoordinator.enroll("tok", "tenant-a", okHw) }
            assertNull(challengeSpki)
            assertTrue(
                java.util.Arrays.equals(
                    provisioner.lastAttestationChallenge,
                    java.util.Base64.getDecoder().decode("Y2hhbGxlbmdl")
                )
            )
            assertEquals("REGISTERED", enrolled.lifecycleState)
            assertNotNull(capturedSig)
            assertTrue(store.current()!!.isEnrolled())
            assertFalse(enrolled.allowedCapabilities.contains("EE_BINDING"))

            val prohibited = mapOf("tmk" to "00FF", "device_id" to "dev-1")
            var prohibitedCaught = false
            try {
                PdsManifestVerifier.assertNoProhibitedManifestMaterial(prohibited)
            } catch (_: SecurityException) {
                prohibitedCaught = true
            }
            assertTrue(prohibitedCaught)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `request signatures bind body hash device id and reject modified bodies`() {
        val pair = generateSoftwareP256()
        val signer = NonExportableSoftwareSigner(pair)
        val originalBody = """{"device_id":"550e8400-e29b-41d4-a716-446655440001","op":"lock"}"""
            .toByteArray(StandardCharsets.UTF_8)
        val params = PdsEnrollmentCrypto.DeviceRequestSigningParams(
            method = "POST",
            path = "/api/v1/device/pds/manifest/status",
            canonicalQuery = "",
            bodyBytes = originalBody,
            deviceId = "550e8400-e29b-41d4-a716-446655440001",
            keyId = "devkey-550e8400-v1",
            timestampSec = 1762084800L,
            nonce = "bm9uY2UtMQ"
        )
        val bytes = PdsEnrollmentCrypto.buildDeviceRequestSigningBytes(params)
        val canonical = String(bytes, StandardCharsets.UTF_8)
        assertTrue(canonical.startsWith("pds-req-v1\nPOST\n"))
        assertTrue(canonical.contains("550e8400-e29b-41d4-a716-446655440001"))
        val sig = signer.signPayload(bytes)
        assertTrue(PdsEnrollmentCrypto.verifyDeviceSignature(pair.public, bytes, sig))

        val modified = PdsEnrollmentCrypto.buildDeviceRequestSigningBytes(
            params.copy(bodyBytes = """{"device_id":"550e8400-e29b-41d4-a716-446655440001","op":"wipe"}"""
                .toByteArray(StandardCharsets.UTF_8))
        )
        assertFalse(PdsEnrollmentCrypto.verifyDeviceSignature(pair.public, modified, sig))
        assertFalse(
            java.util.Arrays.equals(bytes, modified)
        )
    }

    @Test
    fun `hardware backing classifier uses KeyInfo across API 26 to 31 and after restart`() {
        val api26Hw = PdsHardwareBackingClassifier.classify(
            alias = "pds_device_key_v1",
            apiLevel = 26,
            isInsideSecureHardware = true,
            securityLevelApi31 = null,
            aliasPresent = true
        )
        assertTrue(api26Hw.isInsideSecureHardware)
        assertFalse(api26Hw.isStrongBox)
        assertEquals("tee", api26Hw.securityLevelName)

        val api26Sw = PdsHardwareBackingClassifier.classify(
            alias = "pds_device_key_v1",
            apiLevel = 26,
            isInsideSecureHardware = false,
            securityLevelApi31 = null,
            aliasPresent = true
        )
        assertFalse(api26Sw.isInsideSecureHardware)

        val api28 = PdsHardwareBackingClassifier.classify(
            alias = "pds_device_key_v1",
            apiLevel = 28,
            isInsideSecureHardware = true,
            securityLevelApi31 = null,
            aliasPresent = true
        )
        assertTrue(api28.isInsideSecureHardware)
        assertFalse(api28.isStrongBox)

        val api31StrongBox = PdsHardwareBackingClassifier.classify(
            alias = "pds_device_key_v1",
            apiLevel = 31,
            isInsideSecureHardware = true,
            securityLevelApi31 = PdsHardwareBackingClassifier.SECURITY_LEVEL_STRONGBOX,
            aliasPresent = true
        )
        assertTrue(api31StrongBox.isStrongBox)
        assertEquals("strongbox", api31StrongBox.securityLevelName)

        val afterRestart = PdsHardwareBackingClassifier.classify(
            alias = "pds_device_key_v1",
            apiLevel = 31,
            isInsideSecureHardware = true,
            securityLevelApi31 = PdsHardwareBackingClassifier.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
            aliasPresent = true
        )
        assertTrue(afterRestart.isInsideSecureHardware)
        assertFalse(afterRestart.isStrongBox)
        assertEquals("keyinfo_api31", afterRestart.inspectionSource)

        val generatedButNotInspected = PdsHardwareBackingClassifier.classify(
            alias = "pds_device_key_v1",
            apiLevel = 28,
            isInsideSecureHardware = null,
            securityLevelApi31 = null,
            aliasPresent = false
        )
        assertFalse(generatedButNotInspected.isInsideSecureHardware)
        assertEquals("absent", generatedButNotInspected.securityLevelName)
    }

    @Test
    fun `enrollment identity policy never persists serial as cryptographic identity`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440001"
        assertTrue(PdsEnrollmentIdentityPolicy.isQuasarDeviceUuid(uuid))
        assertFalse(PdsEnrollmentIdentityPolicy.isQuasarDeviceUuid("TW202611020001"))
        assertFalse(PdsEnrollmentIdentityPolicy.isQuasarDeviceUuid("9774d56d682e549c"))
        assertTrue(PdsEnrollmentIdentityPolicy.isLegacyCompatibilityIdentity("TW202611020001"))
        assertFalse(PdsEnrollmentIdentityPolicy.isLegacyCompatibilityIdentity(uuid))
        assertEquals(
            uuid,
            PdsEnrollmentIdentityPolicy.selectPersistedDeviceId(uuid, "TW202611020001")
        )
        var threw = false
        try {
            PdsEnrollmentIdentityPolicy.selectPersistedDeviceId("TW202611020001", uuid)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
