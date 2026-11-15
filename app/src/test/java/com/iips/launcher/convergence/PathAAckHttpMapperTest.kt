package com.iips.launcher.convergence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PathAAckHttpMapperTest {
    @Test
    fun mapsCommandAckFrameToHttpBodyPreservingProof() {
        val frame = """
            {"type":"command.ack","request_id":"r1","data":{
              "id":"11111111-1111-1111-1111-111111111111",
              "status":"SUCCESS",
              "signature":"proof-b64",
              "device_proof":"proof-b64",
              "ack_nonce":"nonce-aaaa-bbbb",
              "ack_ts":1700000000,
              "note":"done",
              "result":{"value":"SUCCESS"}
            }}
        """.trimIndent()
        val mapped = PathAAckHttpMapper.fromWsFrameJson(frame)
        assertNotNull(mapped)
        assertEquals("11111111-1111-1111-1111-111111111111", mapped!!.commandId)
        assertEquals("SUCCESS", mapped.body["status"])
        assertEquals("proof-b64", mapped.body["signature"])
        assertEquals("nonce-aaaa-bbbb", mapped.body["ack_nonce"])
        assertEquals(1700000000L, (mapped.body["ack_ts"] as Number).toLong())
        assertEquals("done", mapped.body["note"])
        @Suppress("UNCHECKED_CAST")
        val result = mapped.body["result"] as Map<String, Any?>
        assertEquals("SUCCESS", result["value"])
    }

    @Test
    fun mapsRequestAckToReceivedHttpBody() {
        val frame = """
            {"type":"REQUEST_ACK","command_id":"22222222-2222-2222-2222-222222222222",
             "device_proof":"recv-proof","ack_nonce":"nonce-recv-001","ack_ts":1700000001,
             "payload":{"command_id":"22222222-2222-2222-2222-222222222222",
               "device_proof":"recv-proof","ack_nonce":"nonce-recv-001","ack_ts":1700000001,
               "note":"request_ack_received","result":{"value":"ACKNOWLEDGED"}}}
        """.trimIndent()
        val mapped = PathAAckHttpMapper.fromWsFrameJson(frame)
        assertNotNull(mapped)
        assertEquals("RECEIVED", mapped!!.body["status"])
        assertEquals("recv-proof", mapped.body["signature"])
        assertEquals("nonce-recv-001", mapped.body["ack_nonce"])
    }

    @Test
    fun ignoresNonAckFrames() {
        assertNull(PathAAckHttpMapper.fromWsFrameJson("""{"type":"EDGE_TELEMETRY_BATCH"}"""))
        assertNull(PathAAckHttpMapper.fromWsFrameJson("""{"type":"LOCATION_REPORT","data":{}}"""))
    }
}
