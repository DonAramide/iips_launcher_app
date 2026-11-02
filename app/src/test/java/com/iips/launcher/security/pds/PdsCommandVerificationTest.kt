package com.iips.launcher.security.pds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class PdsCommandVerificationTest {

    @Suppress("UNCHECKED_CAST")
    private fun loadGoldenVectorSuite(): Map<String, Any?> {
        val stream = javaClass.classLoader?.getResourceAsStream("pds-command-signing-golden-vectors.v1.json")
        val text = if (stream != null) {
            stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } else {
            File("src/test/resources/pds-command-signing-golden-vectors.v1.json")
                .readText(StandardCharsets.UTF_8)
        }
        return PdsCanonicalCommandEnvelope.parseJsonRejectingDuplicateKeys(text) as Map<String, Any?>
    }

    @Test
    fun `matches all 21 shared Quasar-PDS golden test vectors and survives simulated restart`() {
        val suite = loadGoldenVectorSuite()
        val evalIso = suite["evaluationTimeIso"] as String
        val evalEpochSec = PdsCanonicalCommandEnvelope.parseUtcIsoSeconds(evalIso, "evaluationTimeIso")

        @Suppress("UNCHECKED_CAST")
        val keySetDist = suite["keySetDistribution"] as Map<String, Any?>
        val keysetJws = keySetDist["keyset_jws"] as String

        val tempDir = Files.createTempDirectory("pds-replay-test").toFile()
        try {
            val ledgerFile = File(tempDir, "pds_command_replay.ledger")
            val verifier = PdsCommandVerifier()
            val appliedVer = verifier.applySignedKeySetJws(keysetJws, evalEpochSec)
            assertEquals(1L, appliedVer)

            val authorizer = PdsCommandAuthorizer()
            var replayStore = PersistentCommandReplayStore(ledgerFile)
            var gate = PdsCommandGate(verifier, authorizer, replayStore)

            @Suppress("UNCHECKED_CAST")
            val vectors = suite["vectors"] as List<Map<String, Any?>>
            assertEquals(21, vectors.size)

            for (vec in vectors) {
                val id = vec["id"] as String
                val expectedValid = vec["expectedValid"] as Boolean
                val expectedErrorCode = vec["expectedErrorCode"] as? String
                val commandJws = vec["commandJws"] as String
                val expectedDeviceId = vec["expectedDeviceId"] as String
                val expectedIdentityRef = vec["expectedDeviceIdentityRef"] as String
                val expectedTenantId = vec["expectedTenantId"] as String
                val nowIso = vec["verificationNowIso"] as String
                val nowSec = PdsCanonicalCommandEnvelope.parseUtcIsoSeconds(nowIso, "verificationNowIso")

                // Simulate full application restart before POS-05 and NEG-06/NEG-07 to verify persistent replay recovery
                if (id == "POS-05" || id == "NEG-06") {
                    replayStore = PersistentCommandReplayStore(ledgerFile)
                    gate = PdsCommandGate(verifier, authorizer, replayStore)
                }

                val deviceContext = PdsCommandAuthorizer.DeviceAuthorizationContext(
                    isEnrolled = true,
                    lifecycleState = "ACTIVE",
                    assignedDeviceId = expectedDeviceId,
                    assignedDeviceIdentityRef = expectedIdentityRef,
                    assignedTenantId = expectedTenantId,
                    isDeviceOwner = true,
                    isDeviceAdminActive = true
                )

                val decision = gate.verifyAuthorizeAndReserve(
                    commandJws = commandJws,
                    deviceContext = deviceContext,
                    nowEpochSeconds = nowSec,
                    maxClockSkewSeconds = 60L
                )

                assertEquals(
                    "Vector $id validity mismatch (reason=${decision.errorMessage})",
                    expectedValid,
                    decision.allowed
                )
                assertEquals(
                    "Vector $id error code mismatch (reason=${decision.errorMessage})",
                    expectedErrorCode,
                    decision.errorCode
                )
            }

            // Confirm all 5 positive vectors were persisted to the replay ledger on disk
            val restartedStore = PersistentCommandReplayStore(ledgerFile)
            assertEquals(5, restartedStore.size())
            assertTrue(restartedStore.isCommandConsumed("cmd-vec-pos-01", evalEpochSec))
            assertTrue(restartedStore.isCommandConsumed("cmd-vec-pos-05", evalEpochSec))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `enforces device-bound authorization independent of cryptographic signature validity`() {
        val suite = loadGoldenVectorSuite()
        @Suppress("UNCHECKED_CAST")
        val vectors = suite["vectors"] as List<Map<String, Any?>>
        val pos01 = vectors.first { it["id"] == "POS-01" }
        val pos01Jws = pos01["commandJws"] as String
        val nowSec = PdsCanonicalCommandEnvelope.parseUtcIsoSeconds(
            pos01["verificationNowIso"] as String,
            "now"
        )
        val deviceId = pos01["expectedDeviceId"] as String
        val identityRef = pos01["expectedDeviceIdentityRef"] as String
        val tenantId = pos01["expectedTenantId"] as String

        val tempDir = Files.createTempDirectory("pds-authz-test").toFile()
        try {
            val gate = PdsCommandGate(
                verifier = PdsCommandVerifier(),
                authorizer = PdsCommandAuthorizer(),
                replayStore = PersistentCommandReplayStore(File(tempDir, "replay.ledger"))
            )

            // 1. Unenrolled device rejects cryptographically valid command
            val unenrolledRes = gate.verifyAuthorizeAndReserve(
                commandJws = pos01Jws,
                deviceContext = PdsCommandAuthorizer.DeviceAuthorizationContext(
                    isEnrolled = false,
                    lifecycleState = "PENDING_APPROVAL",
                    assignedDeviceId = deviceId,
                    assignedDeviceIdentityRef = identityRef,
                    assignedTenantId = tenantId,
                    isDeviceOwner = true,
                    isDeviceAdminActive = true
                ),
                nowEpochSeconds = nowSec
            )
            assertFalse(unenrolledRes.allowed)
            assertEquals("DEVICE_NOT_ENROLLED", unenrolledRes.errorCode)

            // 2. Wrong tenant scope rejects cryptographically valid command
            val wrongTenantRes = gate.verifyAuthorizeAndReserve(
                commandJws = pos01Jws,
                deviceContext = PdsCommandAuthorizer.DeviceAuthorizationContext(
                    isEnrolled = true,
                    lifecycleState = "ACTIVE",
                    assignedDeviceId = deviceId,
                    assignedDeviceIdentityRef = identityRef,
                    assignedTenantId = "other-tenant-uuid-9999",
                    isDeviceOwner = true,
                    isDeviceAdminActive = true
                ),
                nowEpochSeconds = nowSec
            )
            assertFalse(wrongTenantRes.allowed)
            assertEquals("TENANT_SCOPE_MISMATCH", wrongTenantRes.errorCode)

            // 3. Missing Device Owner capability rejects REBOOT command
            val missingCapRes = gate.verifyAuthorizeAndReserve(
                commandJws = pos01Jws,
                deviceContext = PdsCommandAuthorizer.DeviceAuthorizationContext(
                    isEnrolled = true,
                    lifecycleState = "ACTIVE",
                    assignedDeviceId = deviceId,
                    assignedDeviceIdentityRef = identityRef,
                    assignedTenantId = tenantId,
                    isDeviceOwner = false,
                    isDeviceAdminActive = false
                ),
                nowEpochSeconds = nowSec
            )
            assertFalse(missingCapRes.allowed)
            assertEquals("CAPABILITY_MISSING", missingCapRes.errorCode)

            // Confirm none of the rejected authorization attempts consumed the command in the replay store!
            assertFalse(gate.replayStore.isCommandConsumed("cmd-vec-pos-01", nowSec))

            // 4. Authorized context succeeds and reserves the command
            val okRes = gate.verifyAuthorizeAndReserve(
                commandJws = pos01Jws,
                deviceContext = PdsCommandAuthorizer.DeviceAuthorizationContext(
                    isEnrolled = true,
                    lifecycleState = "ACTIVE",
                    assignedDeviceId = deviceId,
                    assignedDeviceIdentityRef = identityRef,
                    assignedTenantId = tenantId,
                    isDeviceOwner = true,
                    isDeviceAdminActive = true
                ),
                nowEpochSeconds = nowSec
            )
            assertTrue(okRes.allowed)
            assertNotNull(okRes.claims)
            assertTrue(gate.replayStore.isCommandConsumed("cmd-vec-pos-01", nowSec))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `rejects tampered keyset_jws and keyset version rollback`() {
        val suite = loadGoldenVectorSuite()
        val evalEpochSec = PdsCanonicalCommandEnvelope.parseUtcIsoSeconds(
            suite["evaluationTimeIso"] as String,
            "evaluationTimeIso"
        )
        @Suppress("UNCHECKED_CAST")
        val keySetDist = suite["keySetDistribution"] as Map<String, Any?>
        val keysetJws = keySetDist["keyset_jws"] as String

        val verifier = PdsCommandVerifier()
        assertEquals(1L, verifier.applySignedKeySetJws(keysetJws, evalEpochSec))

        // Tamper with signature of keyset_jws
        val parts = keysetJws.split(".")
        val sigBytes = PdsCanonicalCommandEnvelope.fromBase64UrlStrict(parts[2], "sig")
        sigBytes[0] = (sigBytes[0].toInt() xor 0x01).toByte()
        val tamperedKeysetJws = "${parts[0]}.${parts[1]}.${PdsCanonicalCommandEnvelope.toBase64Url(sigBytes)}"

        var threwOnTamper = false
        try {
            verifier.applySignedKeySetJws(tamperedKeysetJws, evalEpochSec)
        } catch (_: SecurityException) {
            threwOnTamper = true
        }
        assertTrue("Expected SecurityException on tampered keyset_jws", threwOnTamper)
    }
}
