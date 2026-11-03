package com.iips.launcher.security.pds

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdsPhase1C3OnboardingFlowTest {

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

    @Test
    fun `QR payload extracts tenant token and backend including extras bundle`() {
        val json = """
            {"enrollment_token":"tok-abc","backend_url":"https://api.example.test","tenant_id":"$tenant"}
        """.trimIndent()
        val parsed = QrProvisioningPayloadParser.parse(json)
        assertEquals("tok-abc", parsed.enrollmentToken)
        assertEquals("https://api.example.test", parsed.backendUrl)
        assertEquals(tenant, parsed.tenantId)

        val extras = """
            {"android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE":{"enrollment_token":"tok-ex","tenant_id":"$tenant","backend_url":"https://mdm.example.test"}}
        """.trimIndent()
        val fromExtras = QrProvisioningPayloadParser.parse(extras)
        assertEquals("tok-ex", fromExtras.enrollmentToken)
        assertEquals(tenant, fromExtras.tenantId)
    }

    @Test
    fun `onboarding flow persists PDS UUID passes known_device_id and keeps JWT`() {
        var knownSent: String? = null
        val outcome = runBlocking { PdsOnboardingEnrollmentFlow.execute(
            tenantId = tenant,
            enrollmentToken = "tok-abcdefgh",
            manufacturer = "Topwise",
            model = "T1",
            serial = "TW202611020001",
            fingerprintHash = "aa",
            agentCode = null,
            businessName = null,
            hardware = hardware,
            pdsEnroll = { _, _, _ ->
                PdsEnrollmentCoordinator.EnrollmentResult(
                    deviceId = uuid,
                    deviceIdentityRef = "devid:$uuid:k1:abcd",
                    tenantId = tenant,
                    deviceKeyId = "devkey-550e8400-v1",
                    keyVersion = 1L,
                    publicKeyFingerprintSha256 = "ff",
                    lifecycleState = "REGISTERED",
                    attestationTrustTier = "unverified",
                    allowedCapabilities = listOf("COMMAND_EXECUTION"),
                    allowedCommands = listOf("PING"),
                    enrolledAt = "2026-11-02T12:00:00Z"
                )
            },
            legacyRegister = { req ->
                knownSent = req.knownDeviceId
                PdsOnboardingEnrollmentFlow.LegacyRegisterResult(
                    success = true,
                    httpCode = 200,
                    deviceId = "TW202611020001",
                    accessToken = "mdm-jwt-test"
                )
            }
        )
        }
        assertEquals(uuid, knownSent)
        assertEquals(uuid, outcome.persistedDeviceId)
        assertEquals("mdm-jwt-test", outcome.accessToken)
        assertEquals(uuid, outcome.knownDeviceIdSentToLegacy)
    }

    @Test
    fun `missing tenant and failed crypto enrollment fail closed`() {
        var tenantThrew = false
        try {
            runBlocking { PdsOnboardingEnrollmentFlow.execute(
                tenantId = null,
                enrollmentToken = "tok",
                manufacturer = "Topwise",
                model = "T1",
                serial = "TW1",
                fingerprintHash = "aa",
                agentCode = null,
                businessName = null,
                hardware = hardware,
                pdsEnroll = { _, _, _ -> error("must not enroll") },
                legacyRegister = { error("must not register") }
            ) }
        } catch (_: SecurityException) {
            tenantThrew = true
        }
        assertTrue(tenantThrew)

        var cryptoThrew = false
        try {
            runBlocking { PdsOnboardingEnrollmentFlow.execute(
                tenantId = tenant,
                enrollmentToken = "tok",
                manufacturer = "Topwise",
                model = "T1",
                serial = "TW1",
                fingerprintHash = "aa",
                agentCode = null,
                businessName = null,
                hardware = hardware,
                pdsEnroll = { _, _, _ -> throw SecurityException("keystore failed") },
                legacyRegister = { error("must not register") }
            ) }
        } catch (_: SecurityException) {
            cryptoThrew = true
        }
        assertTrue(cryptoThrew)
    }
}
