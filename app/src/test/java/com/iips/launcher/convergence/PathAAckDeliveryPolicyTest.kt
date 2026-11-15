package com.iips.launcher.convergence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Stage 2A.3.7 / 2A.3.7.1 — policy tests for HTTP ack settle without live network.
 * Proves frame→HTTP body preserves signed v2 fields; duplicate-nonce settled;
 * skew is retained (not treated as transient transport retry).
 */
class PathAAckDeliveryPolicyTest {
    @Test
    fun httpBodyReusesExactProofFieldsFromCommandAckFrame() {
        val proof = "signed-v2-proof-value"
        val nonce = "nonce-fixed-001"
        val ts = 1_700_000_100L
        val frame = """
            {"type":"command.ack","data":{
              "id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
              "status":"SUCCESS",
              "signature":"$proof",
              "device_proof":"$proof",
              "ack_nonce":"$nonce",
              "ack_ts":$ts,
              "note":"ok",
              "result":{"value":"SUCCESS"}
            }}
        """.trimIndent()
        val mapped = PathAAckHttpMapper.fromWsFrameJson(frame)
        assertNotNull(mapped)
        assertEquals(proof, mapped!!.body["signature"])
        assertEquals(nonce, mapped.body["ack_nonce"])
        assertEquals(ts, (mapped.body["ack_ts"] as Number).toLong())
        assertEquals("SUCCESS", mapped.body["status"])
        assertEquals("ok", mapped.body["note"])
    }

    @Test
    fun duplicateNonceClassifiedAsSettledNotRetryable() {
        val c = PathAHttpErrorClassifier.classify(409, "ack_nonce_replay")
        assertEquals("ack_nonce_replay", c.code)
        assertFalse(c.retryable)
    }

    @Test
    fun skewIsRetainedNotTransientlyRetried() {
        val c = PathAHttpErrorClassifier.classify(403, "Forbidden: ack_ts_out_of_skew")
        assertFalse(c.retryable)
        assertEquals("ack_ts_out_of_skew", c.code)
    }

    @Test
    fun mapperDoesNotInventProofWhenMissing() {
        val frame = """
            {"type":"command.ack","data":{
              "id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
              "status":"SUCCESS",
              "ack_nonce":"nonce-fixed-002",
              "ack_ts":1700000200
            }}
        """.trimIndent()
        assertEquals(null, PathAAckHttpMapper.fromWsFrameJson(frame))
    }
}
