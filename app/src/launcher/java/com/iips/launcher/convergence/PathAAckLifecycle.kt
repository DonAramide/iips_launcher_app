package com.iips.launcher.convergence

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage 2A.3.7.2 — synchronized acknowledgement capacity reservation + recovery.
 *
 * In-process safety via store locks. Not cross-process/cross-instance safe.
 */
@Singleton
class PathAAckLifecycle @Inject constructor(
    private val store: PathAAckPendingStore
) {
    companion object {
        private const val TAG = "PathAAckLifecycle"
    }

    fun tryReserveBeforeExecution(commandId: String): PathAAckPendingQueue.ReserveResult {
        val result = store.tryReserve(commandId)
        when (result) {
            is PathAAckPendingQueue.ReserveResult.Ok ->
                Log.i(TAG, "Reserved ack capacity for command=$commandId reservation=${result.reservationId}")
            is PathAAckPendingQueue.ReserveResult.Rejected ->
                Log.e(TAG, "Ack capacity reservation rejected code=${result.code}: ${result.message}")
        }
        return result
    }

    fun markReplayCommitted(reservationId: String): Boolean =
        store.updateRecords { PathAAckPendingQueue.markReplayCommitted(it, reservationId) }

    fun markExecuting(reservationId: String): Boolean =
        store.updateRecords { PathAAckPendingQueue.markExecuting(it, reservationId) }

    fun markExecutionUncertain(reservationId: String, error: String): Boolean =
        store.updateRecords { PathAAckPendingQueue.markExecutionUncertain(it, reservationId, error) }

    fun markRecoveryRequired(reservationId: String, error: String): Boolean =
        store.updateRecords { PathAAckPendingQueue.markRecoveryRequired(it, reservationId, error) }

    fun releaseReservationIfSafe(reservationId: String): Boolean {
        var released = false
        store.updateRecords {
            val (next, ok) = PathAAckPendingQueue.releaseReservationIfSafe(it, reservationId)
            released = ok
            next
        }
        return released
    }

    fun enqueueSignedFrame(
        frameJson: String,
        reservationId: String?
    ): PathAAckPendingQueue.EnqueueResult =
        store.enqueueExactFrame(frameJson, reservationId)

    fun reconcileAfterProcessStart() {
        if (store.isCorrupt()) {
            Log.e(TAG, "Ack store corrupt — recovery required; skipping lease reconcile mutations")
            return
        }
        store.reconcileAfterProcessStart()
        if (store.isRecoveryRequired()) {
            Log.e(TAG, "Path A ack recovery required after process start (no auto re-execute)")
        }
    }

    fun blocksNewExecution(): Boolean =
        store.isCorrupt() || store.isRecoveryRequired()

    fun pendingStore(): PathAAckPendingStore = store
}
