package com.iips.launcher.convergence

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoMdmWsErrorFrameTest {
    @Test
    fun parsesSanitizedProofRejection() {
        val root = JsonParser.parseString(
            """{"type":"error","request_id":"r1","data":{"message":"Device acknowledgement proof rejected"}}"""
        ).asJsonObject
        val parsed = DoMdmWsErrorFrame.parse(root)!!
        assertEquals("r1", parsed.requestId)
        assertEquals("invalid_device_proof", parsed.code)
        assertFalse(parsed.retryable)
    }

    @Test
    fun classifiesRetryableSkew() {
        val c = DoMdmWsErrorFrame.classify("Acknowledgement timestamp out of allowed skew")
        assertEquals("ack_ts_out_of_skew", c.first)
        assertTrue(c.second)
    }

    @Test
    fun classifiesNonceReplayPermanent() {
        val c = DoMdmWsErrorFrame.classify("Acknowledgement nonce already used")
        assertEquals("ack_nonce_replay", c.first)
        assertFalse(c.second)
    }

    @Test
    fun classifiesNotFoundForbiddenExpired() {
        assertEquals("command_not_found", DoMdmWsErrorFrame.classify("Command not found for this device").first)
        assertEquals("forbidden", DoMdmWsErrorFrame.classify("Acknowledgement forbidden").first)
        assertEquals("command_expired", DoMdmWsErrorFrame.classify("Command expired").first)
        assertEquals("invalid_ack_payload", DoMdmWsErrorFrame.classify("Acknowledgement payload rejected").first)
        assertEquals("unauthorized_socket", DoMdmWsErrorFrame.classify("unauthorized").first)
        assertEquals("protocol_error", DoMdmWsErrorFrame.classify("Unknown message type: HEARTBEAT_PING").first)
        assertTrue(DoMdmWsErrorFrame.classify("Temporary acknowledgement failure").second)
    }

    @Test
    fun malformedEmptyMessageFailsClosed() {
        val root = JsonParser.parseString("""{"type":"error","data":{}}""").asJsonObject
        val parsed = DoMdmWsErrorFrame.parse(root)!!
        assertEquals("ack_rejected", parsed.code)
        assertFalse(parsed.retryable)
    }

    @Test
    fun nonErrorTypeReturnsNull() {
        val root = JsonParser.parseString("""{"type":"command.ack.ok","data":{}}""").asJsonObject
        assertNull(DoMdmWsErrorFrame.parse(root))
    }
}
