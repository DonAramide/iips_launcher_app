package com.iips.launcher.security.pds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R-01: HTTP 409 recovery identity is in Retrofit errorBody JSON, not body().
 * JVM unit tests only — not Keystore / StrongBox / physical POS.
 */
class PdsPhase1D4R01RecoveryTest {

    private val uuid = "550e8400-e29b-41d4-a716-446655440001"
    private val tenant = "11111111-2222-3333-4444-555555555555"

    private fun validRecoveryJson(
        errorCode: String = PdsEnrollmentHttpErrors.ENROLLMENT_ALREADY_COMPLETED,
        deviceId: String = uuid,
        includeRequired: Boolean = true
    ): String {
        val base = """
            "error_code":"$errorCode",
            "device_id":"$deviceId"
        """.trimIndent()
        if (!includeRequired) {
            return "{$base}"
        }
        return """
            {
              $base,
              "device_identity_ref":"devid:$deviceId:k1:abcd",
              "tenant_id":"$tenant",
              "device_key_id":"devkey-v1",
              "key_version":1,
              "public_key_fingerprint_sha256":"abc123",
              "lifecycle_state":"REGISTERED",
              "attestation_trust_tier":"software",
              "allowed_capabilities":[],
              "allowed_commands":[],
              "enrolled_at":"2026-11-03T00:00:00Z"
            }
        """.trimIndent()
    }

    @Test
    fun `409 enrollment_already_completed with valid identity parses`() {
        val parsed = PdsEnrollmentHttpErrors.parseRecoveryEnrollmentResult(validRecoveryJson())
        assertNotNull(parsed)
        assertEquals(uuid, parsed!!.deviceId)
        assertEquals(tenant, parsed.tenantId)
        assertEquals("devkey-v1", parsed.deviceKeyId)
    }

    @Test
    fun `409 unrelated error_code is rejected`() {
        val json = validRecoveryJson(errorCode = "enrollment_challenge_replayed")
        assertNull(PdsEnrollmentHttpErrors.parseRecoveryEnrollmentResult(json))
        assertEquals(
            "enrollment_challenge_replayed",
            PdsEnrollmentHttpErrors.parseErrorCode(json)
        )
    }

    @Test
    fun `409 malformed JSON is rejected`() {
        assertNull(PdsEnrollmentHttpErrors.parseRecoveryEnrollmentResult("{not-json"))
        assertNull(PdsEnrollmentHttpErrors.parseRecoveryEnrollmentResult(""))
        assertNull(PdsEnrollmentHttpErrors.parseRecoveryEnrollmentResult(null))
    }

    @Test
    fun `409 missing device UUID is rejected`() {
        assertNull(
            PdsEnrollmentHttpErrors.parseRecoveryEnrollmentResult(
                validRecoveryJson(deviceId = "not-a-uuid")
            )
        )
    }

    @Test
    fun `409 missing required fields is rejected`() {
        assertNull(
            PdsEnrollmentHttpErrors.parseRecoveryEnrollmentResult(
                validRecoveryJson(includeRequired = false)
            )
        )
    }

    @Test
    fun `shouldEnterRecovery only for exact code`() {
        assertTrue(
            PdsEnrollmentHttpErrors.shouldEnterRecovery(
                409,
                PdsEnrollmentHttpErrors.ENROLLMENT_ALREADY_COMPLETED
            )
        )
        assertTrue(
            !PdsEnrollmentHttpErrors.shouldEnterRecovery(409, "identity_mismatch")
        )
        assertTrue(
            !PdsEnrollmentHttpErrors.shouldEnterRecovery(500, PdsEnrollmentHttpErrors.ENROLLMENT_ALREADY_COMPLETED)
        )
    }

    @Test
    fun `coordinator recovery retry uses recovered UUID from transport`() {
        val pair = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val signer = object : PdsDeviceKeySigner {
            override fun keyAlias() = "pds_device_key_v1"
            override fun publicKeySpkiB64(): String =
                java.util.Base64.getEncoder().encodeToString(pair.public.encoded)
            override fun publicKeyFingerprintSha256(): String =
                PdsEnrollmentCrypto.publicKeyFingerprintSha256(pair.public.encoded)
            override fun signPayload(payloadBytes: ByteArray): String {
                val s = java.security.Signature.getInstance("SHA256withECDSA")
                s.initSign(pair.private)
                s.update(payloadBytes)
                return PdsCanonicalCommandEnvelope.toBase64Url(s.sign())
            }
            override fun attestationChainB64(): List<String> = emptyList()
            override fun isStrongBoxBacked() = false
            override fun isHardwareBacked() = false
            override fun inspectHardwareBacking() =
                PdsHardwareBackingClassifier.classify(
                    alias = keyAlias(),
                    apiLevel = 30,
                    isInsideSecureHardware = false,
                    securityLevelApi31 = PdsHardwareBackingClassifier.SECURITY_LEVEL_SOFTWARE,
                    aliasPresent = true
                )
            override fun exportPrivateKeyBytes(): ByteArray {
                throw UnsupportedOperationException("non-exportable")
            }
        }
        val tempDir = java.nio.file.Files.createTempDirectory("pds-r01").toFile()
        try {
            val hardware = PdsEnrollmentCrypto.HardwareAttributes(
                manufacturer = "Topwise",
                model = "T1",
                hardwareId = "hw-1",
                serialNumber = "TW202611030001",
                androidVersion = "11",
                sdkInt = 30L,
                buildFingerprint = "Topwise/T1/T1:11/release-keys",
                pdsVersion = "1.0.0",
                vendor = "topwise"
            )
            var verifyCount = 0
            val transport = object : PdsEnrollmentCoordinator.PdsEnrollmentTransport {
                override suspend fun requestChallenge(
                    enrollmentToken: String,
                    tenantId: String,
                    devicePublicKeySpkiB64: String?,
                    hardware: PdsEnrollmentCrypto.HardwareAttributes
                ) = PdsEnrollmentCoordinator.ChallengeResponse(
                    challengeId = "chl-r01",
                    challengeNonce = "bm9uY2U",
                    attestationChallengeB64 = "Y2hhbGxlbmdl",
                    tenantId = tenant,
                    publicKeyFingerprintSha256 = null,
                    hardwareFingerprintSha256 =
                        PdsEnrollmentCrypto.computeCanonicalHardwareFingerprint(hardware),
                    issuedAt = "2026-11-03T00:00:00Z",
                    expiresAt = "2099-01-01T00:00:00Z"
                )

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
                    verifyCount += 1
                    if (verifyCount == 1) {
                        throw PdsEnrollmentNeedsRecoveryException(challengeId)
                    }
                    // Simulate errorBody-derived recovery result (what transport returns after R-01)
                    if (recoveryNonce.isNullOrBlank() || recoverySignatureB64Url.isNullOrBlank()) {
                        fail("recovery proof required on retry")
                    }
                    return PdsEnrollmentCoordinator.EnrollmentResult(
                        deviceId = uuid,
                        deviceIdentityRef = "devid:$uuid:k1:abcd",
                        tenantId = tenant,
                        deviceKeyId = "devkey-v1",
                        keyVersion = 1L,
                        publicKeyFingerprintSha256 = signer.publicKeyFingerprintSha256(),
                        lifecycleState = "REGISTERED",
                        attestationTrustTier = "software",
                        allowedCapabilities = emptyList(),
                        allowedCommands = emptyList(),
                        enrolledAt = "2026-11-03T00:00:00Z"
                    )
                }
            }
            val coordinator = testCoordinator(
                signer = signer,
                store = PdsEnrollmentStateStore(java.io.File(tempDir, "state")),
                transport = transport,
                tempDir = tempDir,
                provisioner = NoopIfKeyPresentProvisioner(signer),
                pendingStore = PdsPendingEnrollmentStore(java.io.File(tempDir, "pending"))
            )
            val enrolled = kotlinx.coroutines.runBlocking {
                coordinator.enroll("tok-abcdefgh", tenant, hardware)
            }
            assertEquals(uuid, enrolled.deviceId)
            assertEquals(2, verifyCount)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
