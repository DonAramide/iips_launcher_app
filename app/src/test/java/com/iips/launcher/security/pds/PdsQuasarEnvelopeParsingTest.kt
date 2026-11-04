package com.iips.launcher.security.pds

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.iips.launcher.network.models.PdsEnrollmentChallengeResponse
import com.iips.launcher.network.models.PdsEnrollmentVerifyResponse
import com.iips.launcher.network.models.PdsDeviceIdentityStatusResponse
import com.iips.launcher.network.models.QuasarApiEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 2.2B: Quasar envelope unwrap for PDS success and recovery errors.
 */
class PdsQuasarEnvelopeParsingTest {

    private val gson = Gson()
    private val uuid = "550e8400-e29b-41d4-a716-446655440001"
    private val tenant = "11111111-2222-3333-4444-555555555555"

    private fun challengeEnvelopeJson(): String = """
        {
          "responseCode":"00",
          "responseMessage":"Success",
          "data":{
            "challenge_id":"chl-1",
            "challenge_nonce":"nonce-1",
            "attestation_challenge_b64":"YQ==",
            "tenant_id":"$tenant",
            "public_key_fingerprint_sha256":null,
            "hardware_fingerprint_sha256":"hwfp",
            "issued_at":"2026-11-04T00:00:00Z",
            "expires_at":"2026-11-04T00:05:00Z"
          }
        }
    """.trimIndent()

    private fun verifyEnvelopeJson(): String = """
        {
          "responseCode":"00",
          "responseMessage":"Success",
          "data":{
            "device_id":"$uuid",
            "device_identity_ref":"devid:$uuid:k1:abcd",
            "tenant_id":"$tenant",
            "device_key_id":"devkey-v1",
            "key_version":1,
            "public_key_fingerprint_sha256":"abc123",
            "lifecycle_state":"REGISTERED",
            "attestation_trust_tier":"unverified",
            "allowed_capabilities":[],
            "allowed_commands":[],
            "enrolled_at":"2026-11-04T00:00:00Z"
          }
        }
    """.trimIndent()

    private fun recoveryEnvelopeJson(
        errorCode: String = PdsEnrollmentHttpErrors.ENROLLMENT_ALREADY_COMPLETED,
        deviceId: String = uuid,
        includeRequired: Boolean = true
    ): String {
        val data = if (includeRequired) {
            """
            {
              "error_code":"$errorCode",
              "device_id":"$deviceId",
              "device_identity_ref":"devid:$deviceId:k1:abcd",
              "tenant_id":"$tenant",
              "device_key_id":"devkey-v1",
              "key_version":1,
              "public_key_fingerprint_sha256":"abc123",
              "lifecycle_state":"REGISTERED",
              "attestation_trust_tier":"software",
              "allowed_capabilities":[],
              "allowed_commands":[],
              "enrolled_at":"2026-11-04T00:00:00Z"
            }
            """.trimIndent()
        } else {
            """{"error_code":"$errorCode","device_id":"$deviceId"}"""
        }
        return """
            {
              "responseCode":"05",
              "responseMessage":"$errorCode",
              "data":$data
            }
        """.trimIndent()
    }

    @Test
    fun `challenge success envelope unwraps data`() {
        val type = object : TypeToken<QuasarApiEnvelope<PdsEnrollmentChallengeResponse>>() {}.type
        val env: QuasarApiEnvelope<PdsEnrollmentChallengeResponse> =
            gson.fromJson(challengeEnvelopeJson(), type)
        assertEquals("00", env.responseCode)
        assertNotNull(env.data)
        assertEquals("chl-1", env.data!!.challengeId)
        assertEquals(tenant, env.data!!.tenantId)
        assertNull(env.data!!.publicKeyFingerprintSha256)
    }

    @Test
    fun `verify success envelope unwraps data`() {
        val type = object : TypeToken<QuasarApiEnvelope<PdsEnrollmentVerifyResponse>>() {}.type
        val env: QuasarApiEnvelope<PdsEnrollmentVerifyResponse> =
            gson.fromJson(verifyEnvelopeJson(), type)
        assertEquals("00", env.responseCode)
        assertEquals(uuid, env.data!!.deviceId)
        assertEquals("REGISTERED", env.data!!.lifecycleState)
    }

    @Test
    fun `identity success envelope unwraps data`() {
        val json = """
            {
              "responseCode":"00",
              "responseMessage":"Success",
              "data":{
                "device_id":"$uuid",
                "tenant_id":"$tenant",
                "pds_lifecycle_state":"ACTIVE"
              }
            }
        """.trimIndent()
        val type = object : TypeToken<QuasarApiEnvelope<PdsDeviceIdentityStatusResponse>>() {}.type
        val env: QuasarApiEnvelope<PdsDeviceIdentityStatusResponse> = gson.fromJson(json, type)
        assertEquals(uuid, env.data!!.deviceId)
        assertEquals("ACTIVE", env.data!!.lifecycleState)
    }

    @Test
    fun `409 envelope recovery extracts identity from data`() {
        val parsed = PdsEnrollmentHttpErrors.parseRecoveryEnrollmentResult(
            recoveryEnvelopeJson()
        )
        assertNotNull(parsed)
        assertEquals(uuid, parsed!!.deviceId)
        assertEquals(tenant, parsed.tenantId)
        assertEquals("devkey-v1", parsed.deviceKeyId)
    }

    @Test
    fun `409 envelope reads error_code from data`() {
        assertEquals(
            PdsEnrollmentHttpErrors.ENROLLMENT_ALREADY_COMPLETED,
            PdsEnrollmentHttpErrors.parseErrorCode(recoveryEnvelopeJson())
        )
        assertTrue(
            PdsEnrollmentHttpErrors.shouldEnterRecovery(
                409,
                PdsEnrollmentHttpErrors.parseErrorCode(recoveryEnvelopeJson())
            )
        )
    }

    @Test
    fun `409 envelope with unrelated error_code is not recovery`() {
        val json = recoveryEnvelopeJson(errorCode = "identity_mismatch")
        assertEquals("identity_mismatch", PdsEnrollmentHttpErrors.parseErrorCode(json))
        assertNull(PdsEnrollmentHttpErrors.parseRecoveryEnrollmentResult(json))
        assertTrue(
            !PdsEnrollmentHttpErrors.shouldEnterRecovery(
                409,
                PdsEnrollmentHttpErrors.parseErrorCode(json)
            )
        )
    }

    @Test
    fun `409 envelope missing data identity fields is rejected`() {
        assertNull(
            PdsEnrollmentHttpErrors.parseRecoveryEnrollmentResult(
                recoveryEnvelopeJson(includeRequired = false)
            )
        )
    }

    @Test
    fun `malformed and empty error bodies are rejected`() {
        assertNull(PdsEnrollmentHttpErrors.parseErrorCode(null))
        assertNull(PdsEnrollmentHttpErrors.parseErrorCode(""))
        assertNull(PdsEnrollmentHttpErrors.parseErrorCode("{not-json"))
        assertNull(PdsEnrollmentHttpErrors.parseRecoveryEnrollmentResult(null))
        assertNull(PdsEnrollmentHttpErrors.parseRecoveryEnrollmentResult(""))
        assertNull(PdsEnrollmentHttpErrors.parseRecoveryEnrollmentResult("{not-json"))
    }

    @Test
    fun `success envelope with missing data is detectable`() {
        val json = """{"responseCode":"00","responseMessage":"Success","data":null}"""
        val type = object : TypeToken<QuasarApiEnvelope<PdsEnrollmentVerifyResponse>>() {}.type
        val env: QuasarApiEnvelope<PdsEnrollmentVerifyResponse> = gson.fromJson(json, type)
        assertEquals("00", env.responseCode)
        assertNull(env.data)
    }

    @Test
    fun `flat legacy recovery body remains parseable`() {
        val flat = """
            {
              "error_code":"enrollment_already_completed",
              "device_id":"$uuid",
              "device_identity_ref":"devid:$uuid:k1:abcd",
              "tenant_id":"$tenant",
              "device_key_id":"devkey-v1",
              "key_version":1,
              "public_key_fingerprint_sha256":"abc123",
              "lifecycle_state":"REGISTERED",
              "attestation_trust_tier":"software",
              "allowed_capabilities":[],
              "allowed_commands":[],
              "enrolled_at":"2026-11-04T00:00:00Z"
            }
        """.trimIndent()
        assertEquals(
            PdsEnrollmentHttpErrors.ENROLLMENT_ALREADY_COMPLETED,
            PdsEnrollmentHttpErrors.parseErrorCode(flat)
        )
        assertNotNull(PdsEnrollmentHttpErrors.parseRecoveryEnrollmentResult(flat))
    }
}
