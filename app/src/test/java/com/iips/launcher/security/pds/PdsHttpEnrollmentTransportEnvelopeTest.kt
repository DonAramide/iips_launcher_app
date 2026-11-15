package com.iips.launcher.security.pds

import com.iips.launcher.network.ConfigService
import com.iips.launcher.network.models.PdsDeviceIdentityStatusResponse
import com.iips.launcher.network.models.PdsEnrollmentChallengeRequest
import com.iips.launcher.network.models.PdsEnrollmentChallengeResponse
import com.iips.launcher.network.models.PdsEnrollmentVerifyRequest
import com.iips.launcher.network.models.PdsEnrollmentVerifyResponse
import com.iips.launcher.network.models.QuasarApiEnvelope
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 2.2B: envelope unwrap + single errorBody consumption on the HTTP transport.
 */
class PdsHttpEnrollmentTransportEnvelopeTest {

    private val uuid = "550e8400-e29b-41d4-a716-446655440001"
    private val tenant = "11111111-2222-3333-4444-555555555555"

    private val hardware = PdsEnrollmentCrypto.HardwareAttributes(
        manufacturer = "Topwise",
        model = "T1",
        hardwareId = "hw-1",
        serialNumber = "SN1",
        androidVersion = "11",
        sdkInt = 30L,
        buildFingerprint = "fp",
        pdsVersion = "1.0.0",
        vendor = "topwise"
    )

    private fun onceBody(json: String): Pair<ResponseBody, AtomicInteger> {
        val reads = AtomicInteger(0)
        val media = "application/json".toMediaType()
        val body = object : ResponseBody() {
            override fun contentType() = media
            override fun contentLength() = json.toByteArray(Charsets.UTF_8).size.toLong()
            override fun source(): BufferedSource {
                reads.incrementAndGet()
                return Buffer().writeString(json, Charsets.UTF_8)
            }
        }
        return body to reads
    }

    private fun fakeService(
        challenge: Response<QuasarApiEnvelope<PdsEnrollmentChallengeResponse>>? = null,
        verify: Response<QuasarApiEnvelope<PdsEnrollmentVerifyResponse>>? = null
    ): ConfigService = object : ConfigService {
        override suspend fun fetchConfig(url: String) = error("unused")
        override suspend fun enrollDevice(request: com.iips.launcher.network.models.EnrollmentRequest) =
            error("unused")
        override suspend fun pdsEnrollmentChallenge(request: PdsEnrollmentChallengeRequest) =
            challenge ?: error("no challenge stub")
        override suspend fun pdsEnrollmentVerify(request: PdsEnrollmentVerifyRequest) =
            verify ?: error("no verify stub")
        override suspend fun pdsDeviceIdentityStatus(
            deviceId: String,
            tenantId: String,
            enrollmentToken: String
        ): Response<QuasarApiEnvelope<PdsDeviceIdentityStatusResponse>> = error("unused")
        override suspend fun fetchPolicy(authHeader: String) = error("unused")
        override suspend fun getDevicePolicy(deviceId: String) = error("unused")
        override suspend fun sendHeartbeat(
            authHeader: String,
            signature: String,
            timestamp: String,
            nonce: String,
            request: com.iips.launcher.network.models.HeartbeatRequest
        ) = error("unused")
        override suspend fun sendHeartbeatBatch(
            authHeader: String,
            signature: String,
            timestamp: String,
            nonce: String,
            requests: List<com.iips.launcher.network.models.HeartbeatRequest>
        ) = error("unused")
        override suspend fun sendEvent(
            authHeader: String,
            request: com.iips.launcher.network.models.MdmEventRequest
        ) = error("unused")
        override suspend fun reportInventory(
            authHeader: String,
            signature: String,
            inventory: Map<String, Any>
        ) = error("unused")
        override suspend fun fetchAppCatalog(authHeader: String) = error("unused")
        override suspend fun reportAppPocketAudits(
            authHeader: String,
            audits: List<com.iips.launcher.network.models.AppPocketAuditRequest>
        ) = error("unused")
    }

    @Test
    fun `challenge success unwraps Quasar data envelope`() = runBlocking {
        val data = PdsEnrollmentChallengeResponse(
            challengeId = "chl-1",
            challengeNonce = "nonce",
            attestationChallengeB64 = "YQ==",
            tenantId = tenant,
            publicKeyFingerprintSha256 = null,
            hardwareFingerprintSha256 = "hwfp",
            issuedAt = "2026-11-04T00:00:00Z",
            expiresAt = "2026-11-04T00:05:00Z"
        )
        val transport = PdsHttpEnrollmentTransport(
            fakeService(
                challenge = Response.success(
                    QuasarApiEnvelope(responseCode = "00", responseMessage = "Success", data = data)
                )
            )
        )
        val result = transport.requestChallenge("tok", tenant, null, hardware)
        assertEquals("chl-1", result.challengeId)
        assertEquals(tenant, result.tenantId)
    }

    @Test
    fun `verify success unwraps Quasar data envelope`() = runBlocking {
        val data = PdsEnrollmentVerifyResponse(
            deviceId = uuid,
            deviceIdentityRef = "devid:$uuid:k1:abcd",
            tenantId = tenant,
            deviceKeyId = "devkey-v1",
            keyVersion = 1L,
            publicKeyFingerprintSha256 = "abc123",
            lifecycleState = "REGISTERED",
            attestationTrustTier = "unverified",
            allowedCapabilities = emptyList(),
            allowedCommands = emptyList(),
            enrolledAt = "2026-11-04T00:00:00Z"
        )
        val transport = PdsHttpEnrollmentTransport(
            fakeService(
                verify = Response.success(
                    QuasarApiEnvelope(responseCode = "00", responseMessage = "Success", data = data)
                )
            )
        )
        val result = transport.verifyEnrollment(
            challengeId = "chl",
            enrollmentToken = "tok",
            tenantId = tenant,
            devicePublicKeySpkiB64 = "spki",
            hardware = hardware,
            challengeSignatureB64Url = "sig",
            attestation = null,
            recoveryNonce = null,
            recoverySignatureB64Url = null,
            claimedDeviceId = null
        )
        assertEquals(uuid, result.deviceId)
        assertEquals("REGISTERED", result.lifecycleState)
    }

    @Test
    fun `verify success with missing data throws`() = runBlocking {
        val transport = PdsHttpEnrollmentTransport(
            fakeService(
                verify = Response.success(
                    QuasarApiEnvelope<PdsEnrollmentVerifyResponse>(
                        responseCode = "00",
                        responseMessage = "Success",
                        data = null
                    )
                )
            )
        )
        try {
            transport.verifyEnrollment(
                challengeId = "chl",
                enrollmentToken = "tok",
                tenantId = tenant,
                devicePublicKeySpkiB64 = "spki",
                hardware = hardware,
                challengeSignatureB64Url = "sig",
                attestation = null,
                recoveryNonce = null,
                recoverySignatureB64Url = null,
                claimedDeviceId = null
            )
            fail("expected missing data failure")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("missing data"))
        }
    }

    @Test
    fun `409 enrollment_already_completed without proof signals recovery`() = runBlocking {
        val json = """
            {
              "responseCode":"05",
              "responseMessage":"Enrollment already completed",
              "data":{
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
            }
        """.trimIndent()
        val (body, reads) = onceBody(json)
        val transport = PdsHttpEnrollmentTransport(
            fakeService(verify = Response.error(409, body))
        )
        try {
            transport.verifyEnrollment(
                challengeId = "chl",
                enrollmentToken = "tok",
                tenantId = tenant,
                devicePublicKeySpkiB64 = "spki",
                hardware = hardware,
                challengeSignatureB64Url = "sig",
                attestation = null,
                recoveryNonce = null,
                recoverySignatureB64Url = null,
                claimedDeviceId = null
            )
            fail("expected recovery signal")
        } catch (_: PdsEnrollmentNeedsRecoveryException) {
            // expected — identity not disclosed before proof
        }
        assertEquals(1, reads.get())
    }

    @Test
    fun `409 recovery with proof extracts identity from data and reads errorBody once`() = runBlocking {
        val json = """
            {
              "responseCode":"05",
              "responseMessage":"Enrollment already completed",
              "data":{
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
            }
        """.trimIndent()
        val (body, reads) = onceBody(json)
        val transport = PdsHttpEnrollmentTransport(
            fakeService(verify = Response.error(409, body))
        )
        val result = transport.verifyEnrollment(
            challengeId = "chl",
            enrollmentToken = "tok",
            tenantId = tenant,
            devicePublicKeySpkiB64 = "spki",
            hardware = hardware,
            challengeSignatureB64Url = "sig",
            attestation = null,
            recoveryNonce = "rn",
            recoverySignatureB64Url = "rsig",
            claimedDeviceId = uuid
        )
        assertEquals(uuid, result.deviceId)
        assertEquals(tenant, result.tenantId)
        assertEquals("devkey-v1", result.deviceKeyId)
        assertEquals(1, reads.get())
    }

    @Test
    fun `409 unrelated error_code is not completed enrollment`() = runBlocking {
        val json = """
            {
              "responseCode":"05",
              "responseMessage":"identity_mismatch",
              "data":{"error_code":"identity_mismatch","device_id":"$uuid"}
            }
        """.trimIndent()
        val (body, reads) = onceBody(json)
        val transport = PdsHttpEnrollmentTransport(
            fakeService(verify = Response.error(409, body))
        )
        try {
            transport.verifyEnrollment(
                challengeId = "chl",
                enrollmentToken = "tok",
                tenantId = tenant,
                devicePublicKeySpkiB64 = "spki",
                hardware = hardware,
                challengeSignatureB64Url = "sig",
                attestation = null,
                recoveryNonce = null,
                recoverySignatureB64Url = null,
                claimedDeviceId = null
            )
            fail("expected conflict")
        } catch (e: PdsEnrollmentConflictException) {
            assertEquals("identity_mismatch", e.errorCode)
        }
        assertEquals(1, reads.get())
    }

    @Test
    fun `403 preserves sanitized error_code on PdsEnrollmentHttpException`() = runBlocking {
        val errorCode =
            "attestation_failed: KeyDescription attestationChallenge does not match issued enrollment challenge"
        val json = """
            {
              "responseCode":"03",
              "responseMessage":"$errorCode",
              "data":{"error_code":"$errorCode","request_id":"corr-1"}
            }
        """.trimIndent()
        val (body, reads) = onceBody(json)
        val transport = PdsHttpEnrollmentTransport(
            fakeService(verify = Response.error(403, body))
        )
        try {
            transport.verifyEnrollment(
                challengeId = "chl-128cd484-e882-40de-a121-bfe3ef929c2d",
                enrollmentToken = "tok-should-not-appear-in-exception",
                tenantId = tenant,
                devicePublicKeySpkiB64 = "spki-should-not-appear",
                hardware = hardware,
                challengeSignatureB64Url = "sig-should-not-appear",
                attestation = null,
                recoveryNonce = null,
                recoverySignatureB64Url = null,
                claimedDeviceId = null
            )
            fail("expected HTTP 403 exception")
        } catch (e: PdsEnrollmentHttpException) {
            assertEquals(403, e.httpStatus)
            assertEquals(errorCode, e.errorCode)
            assertTrue(e.message!!.contains("error_code=$errorCode"))
            assertTrue(e.message!!.contains("HTTP 403"))
            assertTrue(!e.message!!.contains("tok-should-not-appear"))
            assertTrue(!e.message!!.contains("spki-should-not-appear"))
            assertTrue(!e.message!!.contains("sig-should-not-appear"))
            assertTrue(!e.message!!.contains("certificate_chain"))
        }
        assertEquals(1, reads.get())
    }

    @Test
    fun `403 without error_code still fails closed without retry`() = runBlocking {
        val json = """{"responseCode":"03","responseMessage":"Forbidden","data":null}"""
        val (body, reads) = onceBody(json)
        val transport = PdsHttpEnrollmentTransport(
            fakeService(verify = Response.error(403, body))
        )
        try {
            transport.verifyEnrollment(
                challengeId = "chl",
                enrollmentToken = "tok",
                tenantId = tenant,
                devicePublicKeySpkiB64 = "spki",
                hardware = hardware,
                challengeSignatureB64Url = "sig",
                attestation = null
            )
            fail("expected HTTP 403 exception")
        } catch (e: PdsEnrollmentHttpException) {
            assertEquals(403, e.httpStatus)
            assertEquals(null, e.errorCode)
            assertEquals(
                "PDS enrollment verification failed (HTTP 403)",
                e.message
            )
        }
        assertEquals(1, reads.get())
    }
}
