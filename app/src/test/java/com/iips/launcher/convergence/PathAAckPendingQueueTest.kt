package com.iips.launcher.convergence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathAAckPendingQueueTest {
    private val nowSec = 1_700_000_000L
    private val nowMs = nowSec * 1000L

    private fun frame(commandId: String, nonce: String, ts: Long, proof: String = "proof"): String =
        """{"type":"command.ack","data":{"id":"$commandId","status":"SUCCESS",
            "signature":"$proof","device_proof":"$proof","ack_nonce":"$nonce",
            "ack_ts":$ts,"note":"ok","result":{"value":"SUCCESS"}}}"""

    @Test
    fun enqueuePreservesOrderAndExactFrame() {
        val id1 = "11111111-1111-1111-1111-111111111111"
        val id2 = "22222222-2222-2222-2222-222222222222"
        val f1 = frame(id1, "nonce-aaaa-0001", nowSec)
        val f2 = frame(id2, "nonce-bbbb-0002", nowSec)
        val r1 = PathAAckPendingQueue.enqueue(emptyList(), id1, "nonce-aaaa-0001", nowSec, f1, nowMs)
            as PathAAckPendingQueue.EnqueueResult.Ok
        val r2 = PathAAckPendingQueue.enqueue(r1.records, id2, "nonce-bbbb-0002", nowSec, f2, nowMs)
            as PathAAckPendingQueue.EnqueueResult.Ok
        assertEquals(2, r2.records.size)
        assertEquals(f1, r2.records[0].frameJson)
        assertEquals(f2, r2.records[1].frameJson)
        assertEquals(PathAAckPendingQueue.STATE_PENDING, r2.records[0].state)
    }

    @Test
    fun duplicateNonceDoesNotOverwrite() {
        val id = "11111111-1111-1111-1111-111111111111"
        val f1 = frame(id, "nonce-aaaa-0001", nowSec, "proof-a")
        val f2 = frame(id, "nonce-aaaa-0001", nowSec, "proof-b")
        val r1 = PathAAckPendingQueue.enqueue(emptyList(), id, "nonce-aaaa-0001", nowSec, f1, nowMs)
            as PathAAckPendingQueue.EnqueueResult.Ok
        val dup = PathAAckPendingQueue.enqueue(r1.records, id, "nonce-aaaa-0001", nowSec, f2, nowMs)
        assertTrue(dup is PathAAckPendingQueue.EnqueueResult.Duplicate)
        assertEquals(1, (dup as PathAAckPendingQueue.EnqueueResult.Duplicate).records.size)
        assertEquals("proof-a", PathAAckHttpMapper.fromWsFrameJson(dup.records[0].frameJson)!!.body["signature"])
    }

    @Test
    fun capacityExhaustionDoesNotDropExisting() {
        var records = emptyList<PathAAckPendingQueue.Record>()
        repeat(PathAAckPendingQueue.MAX_PENDING) { i ->
            val id = "aaaaaaaa-bbbb-cccc-dddd-${i.toString().padStart(12, '0')}"
            val nonce = "nonce-${i.toString().padStart(8, '0')}"
            val r = PathAAckPendingQueue.enqueue(
                records, id, nonce, nowSec, frame(id, nonce, nowSec), nowMs
            ) as PathAAckPendingQueue.EnqueueResult.Ok
            records = r.records
        }
        val extraId = "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee"
        val rejected = PathAAckPendingQueue.enqueue(
            records, extraId, "nonce-overflow", nowSec, frame(extraId, "nonce-overflow", nowSec), nowMs
        )
        assertTrue(rejected is PathAAckPendingQueue.EnqueueResult.Rejected)
        assertEquals("capacity_exhausted", (rejected as PathAAckPendingQueue.EnqueueResult.Rejected).code)
        assertEquals(PathAAckPendingQueue.MAX_PENDING, rejected.records.size)
    }

    @Test
    fun skewedAckEnqueuedAsExpiredRetainedWithoutResign() {
        val id = "11111111-1111-1111-1111-111111111111"
        val oldTs = nowSec - PathAAckPendingQueue.ACK_SKEW_SEC - 5
        val f = frame(id, "nonce-skew-0001", oldTs)
        val r = PathAAckPendingQueue.enqueue(emptyList(), id, "nonce-skew-0001", oldTs, f, nowMs)
            as PathAAckPendingQueue.EnqueueResult.Ok
        assertEquals(PathAAckPendingQueue.STATE_EXPIRED_RETAINED, r.records[0].state)
        assertEquals(f, r.records[0].frameJson)
        assertTrue(PathAAckPendingQueue.deliverable(r.records, nowSec).isEmpty())
        assertEquals(1, PathAAckPendingQueue.retainedExpired(r.records).size)
    }

    @Test
    fun clockCorrectionCanReturnExpiredToPending() {
        val id = "11111111-1111-1111-1111-111111111111"
        val ts = nowSec
        val f = frame(id, "nonce-clock-0001", ts)
        val r = PathAAckPendingQueue.enqueue(
            emptyList(), id, "nonce-clock-0001", ts, f, nowMs
        ) as PathAAckPendingQueue.EnqueueResult.Ok
        val expired = PathAAckPendingQueue.reevaluateSkew(r.records, nowSec + PathAAckPendingQueue.ACK_SKEW_SEC + 10)
        assertEquals(PathAAckPendingQueue.STATE_EXPIRED_RETAINED, expired[0].state)
        val recovered = PathAAckPendingQueue.reevaluateSkew(expired, nowSec)
        assertEquals(PathAAckPendingQueue.STATE_PENDING, recovered[0].state)
        assertEquals(f, recovered[0].frameJson)
    }

    @Test
    fun markSettledRemovesOnlyTarget() {
        val id1 = "11111111-1111-1111-1111-111111111111"
        val id2 = "22222222-2222-2222-2222-222222222222"
        val r1 = PathAAckPendingQueue.enqueue(
            emptyList(), id1, "n1", nowSec, frame(id1, "n1", nowSec), nowMs
        ) as PathAAckPendingQueue.EnqueueResult.Ok
        val r2 = PathAAckPendingQueue.enqueue(
            r1.records, id2, "n2", nowSec, frame(id2, "n2", nowSec), nowMs
        ) as PathAAckPendingQueue.EnqueueResult.Ok
        val after = PathAAckPendingQueue.markSettled(r2.records, r2.records[0].recordId)
        assertEquals(1, after.size)
        assertEquals(id2, after[0].commandId)
    }

    @Test
    fun skewWindowMatchesQuasar120Seconds() {
        assertEquals(120L, PathAAckPendingQueue.ACK_SKEW_SEC)
        assertTrue(PathAAckPendingQueue.isWithinSkewWindow(nowSec, nowSec))
        assertTrue(PathAAckPendingQueue.isWithinSkewWindow(nowSec - 120, nowSec))
        assertFalse(PathAAckPendingQueue.isWithinSkewWindow(nowSec - 121, nowSec))
    }
}
