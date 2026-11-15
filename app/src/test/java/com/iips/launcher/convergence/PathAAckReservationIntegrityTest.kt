package com.iips.launcher.convergence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 2A.3.7.2 — capacity reservation, crash reconcile, corruption fence (pure).
 */
class PathAAckReservationIntegrityTest {
    private val nowSec = 1_700_000_000L
    private val nowMs = nowSec * 1000L

    private fun frame(commandId: String, nonce: String, ts: Long = nowSec): String =
        """{"type":"command.ack","data":{"id":"$commandId","status":"SUCCESS",
            "signature":"proof","device_proof":"proof","ack_nonce":"$nonce",
            "ack_ts":$ts,"note":"ok","result":{"value":"SUCCESS"}}}"""

    @Test
    fun reservationSucceedsWhenCapacityExists() {
        val r = PathAAckPendingQueue.reserve(emptyList(), "cmd-1", 3, nowMs, "res-1")
        assertTrue(r is PathAAckPendingQueue.ReserveResult.Ok)
        val ok = r as PathAAckPendingQueue.ReserveResult.Ok
        assertEquals(3, PathAAckPendingQueue.capacityUsed(ok.records))
        assertEquals(PathAAckPendingQueue.STATE_RESERVED, ok.records[0].state)
        assertFalse(ok.records[0].replayCommitted)
    }

    @Test
    fun reservationRejectedWhenCapacityExhaustedPreservesExisting() {
        var records = emptyList<PathAAckPendingQueue.Record>()
        repeat(PathAAckPendingQueue.MAX_PENDING) { i ->
            val id = "aaaaaaaa-bbbb-cccc-dddd-${i.toString().padStart(12, '0')}"
            val nonce = "nonce-${i.toString().padStart(8, '0')}"
            records = (PathAAckPendingQueue.enqueue(
                records, id, nonce, nowSec, frame(id, nonce), nowMs
            ) as PathAAckPendingQueue.EnqueueResult.Ok).records
        }
        val before = records.size
        val rejected = PathAAckPendingQueue.reserve(records, "new-cmd", 3, nowMs, "res-x")
        assertTrue(rejected is PathAAckPendingQueue.ReserveResult.Rejected)
        assertEquals("capacity_exhausted", (rejected as PathAAckPendingQueue.ReserveResult.Rejected).code)
        assertEquals(before, rejected.records.size)
    }

    @Test
    fun concurrentStyleReservesCannotExceedLimit() {
        var records = emptyList<PathAAckPendingQueue.Record>()
        val maxLeases = PathAAckPendingQueue.MAX_PENDING / PathAAckPendingQueue.ACK_SLOTS_PER_COMMAND
        repeat(maxLeases) { i ->
            val r = PathAAckPendingQueue.reserve(records, "cmd-$i", 3, nowMs, "res-$i")
            assertTrue(r is PathAAckPendingQueue.ReserveResult.Ok)
            records = (r as PathAAckPendingQueue.ReserveResult.Ok).records
        }
        // 33*3=99 units; free=1 which cannot satisfy another 3-unit lease
        assertEquals(maxLeases * PathAAckPendingQueue.ACK_SLOTS_PER_COMMAND, PathAAckPendingQueue.capacityUsed(records))
        assertTrue(PathAAckPendingQueue.freeCapacity(records) < PathAAckPendingQueue.ACK_SLOTS_PER_COMMAND)
        val over = PathAAckPendingQueue.reserve(records, "cmd-over", 3, nowMs, "res-over")
        assertTrue(over is PathAAckPendingQueue.ReserveResult.Rejected)
        assertEquals(maxLeases, records.count { it.kind == PathAAckPendingQueue.KIND_LEASE })
    }

    @Test
    fun expiredRetainedConsumesCapacity() {
        val id = "11111111-1111-1111-1111-111111111111"
        val oldTs = nowSec - PathAAckPendingQueue.ACK_SKEW_SEC - 10
        val r = PathAAckPendingQueue.enqueue(
            emptyList(), id, "n1", oldTs, frame(id, "n1", oldTs), nowMs
        ) as PathAAckPendingQueue.EnqueueResult.Ok
        assertEquals(PathAAckPendingQueue.STATE_EXPIRED_RETAINED, r.records[0].state)
        assertEquals(1, PathAAckPendingQueue.capacityUsed(r.records))
        assertEquals(PathAAckPendingQueue.MAX_PENDING - 1, PathAAckPendingQueue.freeCapacity(r.records))
    }

    @Test
    fun enqueueConsumesLeaseUnitWithoutIncreasingCapacity() {
        val reserved = PathAAckPendingQueue.reserve(
            emptyList(), "cmd-a", 3, nowMs, "res-a"
        ) as PathAAckPendingQueue.ReserveResult.Ok
        assertEquals(3, PathAAckPendingQueue.capacityUsed(reserved.records))
        val enq = PathAAckPendingQueue.enqueue(
            reserved.records, "cmd-a", "n1", nowSec, frame("cmd-a", "n1"), nowMs, "res-a"
        ) as PathAAckPendingQueue.EnqueueResult.Ok
        // 2 lease units + 1 ack = 3
        assertEquals(3, PathAAckPendingQueue.capacityUsed(enq.records))
        val lease = enq.records.first { it.kind == PathAAckPendingQueue.KIND_LEASE }
        assertEquals(2, lease.capacityUnits)
    }

    @Test
    fun oversizedFrameRejectedWithoutDroppingLease() {
        val reserved = PathAAckPendingQueue.reserve(
            emptyList(), "cmd-a", 3, nowMs, "res-a"
        ) as PathAAckPendingQueue.ReserveResult.Ok
        val huge = "x".repeat(PathAAckPendingQueue.MAX_FRAME_CHARS + 1)
        val badFrame =
            """{"type":"command.ack","data":{"id":"cmd-a","status":"SUCCESS","signature":"p",
                "device_proof":"p","ack_nonce":"n-huge","ack_ts":$nowSec,"note":"$huge",
                "result":{"value":"SUCCESS"}}}"""
        val rejected = PathAAckPendingQueue.enqueue(
            reserved.records, "cmd-a", "n-huge", nowSec, badFrame, nowMs, "res-a"
        )
        assertTrue(rejected is PathAAckPendingQueue.EnqueueResult.Rejected)
        assertEquals("frame_too_large", (rejected as PathAAckPendingQueue.EnqueueResult.Rejected).code)
        assertEquals(3, PathAAckPendingQueue.capacityUsed(rejected.records))
    }

    @Test
    fun safeReleaseOnlyWithoutReplayCommitment() {
        val reserved = PathAAckPendingQueue.reserve(
            emptyList(), "cmd-a", 3, nowMs, "res-a"
        ) as PathAAckPendingQueue.ReserveResult.Ok
        val (released, ok) = PathAAckPendingQueue.releaseReservationIfSafe(reserved.records, "res-a")
        assertTrue(ok)
        assertEquals(0, PathAAckPendingQueue.capacityUsed(released))

        val committed = PathAAckPendingQueue.markReplayCommitted(reserved.records, "res-a")
        val (kept, notOk) = PathAAckPendingQueue.releaseReservationIfSafe(committed, "res-a")
        assertFalse(notOk)
        assertEquals(3, PathAAckPendingQueue.capacityUsed(kept))
    }

    @Test
    fun crashAfterReservationWithoutReplayReleasesOnReconcile() {
        val reserved = PathAAckPendingQueue.reserve(
            emptyList(), "cmd-a", 3, nowMs, "res-a"
        ) as PathAAckPendingQueue.ReserveResult.Ok
        val after = PathAAckPendingQueue.reconcileAfterProcessStart(reserved.records)
        assertTrue(after.none { it.kind == PathAAckPendingQueue.KIND_LEASE })
    }

    @Test
    fun crashAfterReplayBeforeExecRequiresRecoveryNoAutoExec() {
        var records = (PathAAckPendingQueue.reserve(
            emptyList(), "cmd-a", 3, nowMs, "res-a"
        ) as PathAAckPendingQueue.ReserveResult.Ok).records
        records = PathAAckPendingQueue.markReplayCommitted(records, "res-a")
        val after = PathAAckPendingQueue.reconcileAfterProcessStart(records)
        assertEquals(PathAAckPendingQueue.STATE_RECOVERY_REQUIRED, after[0].state)
        assertTrue(PathAAckPendingQueue.hasBlockingRecovery(after))
    }

    @Test
    fun crashDuringExecutingMarksUncertainNotReexecute() {
        var records = (PathAAckPendingQueue.reserve(
            emptyList(), "cmd-a", 3, nowMs, "res-a"
        ) as PathAAckPendingQueue.ReserveResult.Ok).records
        records = PathAAckPendingQueue.markExecuting(records, "res-a")
        val after = PathAAckPendingQueue.reconcileAfterProcessStart(records)
        assertEquals(PathAAckPendingQueue.STATE_EXECUTION_UNCERTAIN, after[0].state)
        assertTrue(PathAAckPendingQueue.hasBlockingRecovery(after))
    }

    @Test
    fun crashAfterPersistKeepsExactSignedFrameDeliverable() {
        var records = (PathAAckPendingQueue.reserve(
            emptyList(), "cmd-a", 3, nowMs, "res-a"
        ) as PathAAckPendingQueue.ReserveResult.Ok).records
        records = PathAAckPendingQueue.markExecuting(records, "res-a")
        val f = frame("cmd-a", "n-persist")
        records = (PathAAckPendingQueue.enqueue(
            records, "cmd-a", "n-persist", nowSec, f, nowMs, "res-a"
        ) as PathAAckPendingQueue.EnqueueResult.Ok).records
        val afterCrash = PathAAckPendingQueue.reconcileAfterProcessStart(records)
        val deliverable = PathAAckPendingQueue.deliverable(afterCrash, nowSec)
        assertEquals(1, deliverable.size)
        assertEquals(f, deliverable[0].frameJson)
        assertEquals(
            PathAAckPendingQueue.STATE_EXECUTION_UNCERTAIN,
            afterCrash.first { it.kind == PathAAckPendingQueue.KIND_LEASE }.state
        )
    }

    @Test
    fun corruptJsonIsCorruptNotEmptyOk() {
        val d = PathAAckEnvelope.decode("{not-json")
        assertTrue(d is PathAAckEnvelope.DecodeResult.Corrupt)
        assertEquals("json_parse", (d as PathAAckEnvelope.DecodeResult.Corrupt).code)
    }

    @Test
    fun duplicateRecordIdsCorruptEntireDocument() {
        val f1 = frame("c1", "n1").replace("\"", "\\\"")
        val f2 = frame("c2", "n2").replace("\"", "\\\"")
        val raw =
            """{"schemaVersion":2,"integrity":"ok","records":[""" +
                """{"recordId":"same","commandId":"c1","ackNonce":"n1","ackTs":$nowSec,""" +
                """"frameJson":"$f1","state":"PENDING","createdAtMs":$nowMs,""" +
                """"kind":"ACK","capacityUnits":1,"replayCommitted":false},""" +
                """{"recordId":"same","commandId":"c2","ackNonce":"n2","ackTs":$nowSec,""" +
                """"frameJson":"$f2","state":"PENDING","createdAtMs":$nowMs,""" +
                """"kind":"ACK","capacityUnits":1,"replayCommitted":false}]}"""
        val d = PathAAckEnvelope.decode(raw)
        assertTrue(d is PathAAckEnvelope.DecodeResult.Corrupt)
    }

    @Test
    fun roundTripEnvelopePreservesLeaseAndAck() {
        var records = (PathAAckPendingQueue.reserve(
            emptyList(), "cmd-a", 3, nowMs, "res-a"
        ) as PathAAckPendingQueue.ReserveResult.Ok).records
        records = (PathAAckPendingQueue.enqueue(
            records, "cmd-a", "n1", nowSec, frame("cmd-a", "n1"), nowMs, "res-a"
        ) as PathAAckPendingQueue.EnqueueResult.Ok).records
        val encoded = PathAAckEnvelope.encode(records)
        val decoded = PathAAckEnvelope.decode(encoded) as PathAAckEnvelope.DecodeResult.Ok
        assertEquals(2, decoded.records.size)
        assertEquals(3, PathAAckPendingQueue.capacityUsed(decoded.records))
    }

    @Test
    fun unsupportedSchemaVersionIsCorrupt() {
        val raw = """{"schemaVersion":99,"integrity":"ok","records":[]}"""
        val d = PathAAckEnvelope.decode(raw) as PathAAckEnvelope.DecodeResult.Corrupt
        assertEquals("unsupported_schema_version", d.code)
    }
}
