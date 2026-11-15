package com.iips.launcher.convergence

/**
 * Stage 2A.3.7.1 / 2A.3.7.2 — durable Path A acknowledgement + capacity lease semantics.
 *
 * Capacity units (not silent discards):
 * - Signed PENDING / EXPIRED_RETAINED frames consume 1 unit each.
 * - Command leases (RESERVED/EXECUTING/UNCERTAIN/RECOVERY) consume [capacityUnits].
 * - Reservations count against the same MAX_PENDING budget as pending acks.
 */
object PathAAckPendingQueue {
    const val MAX_PENDING = 100
    const val MAX_FRAME_CHARS = 65_536
    const val MAX_AGGREGATE_CHARS = 1_500_000
    const val ACK_SKEW_SEC = 120L
    /** REQUEST_ACK + EXECUTING + terminal — reserved before execution. */
    const val ACK_SLOTS_PER_COMMAND = 3
    const val SCHEMA_VERSION = 2

    const val KIND_ACK = "ACK"
    const val KIND_LEASE = "LEASE"

    const val STATE_RESERVED = "RESERVED"
    const val STATE_EXECUTING = "EXECUTING"
    const val STATE_PENDING = "PENDING"
    const val STATE_PENDING_DELIVERY = "PENDING_DELIVERY"
    const val STATE_DELIVERED = "DELIVERED"
    const val STATE_EXPIRED_RETAINED = "EXPIRED_RETAINED"
    const val STATE_EXECUTION_UNCERTAIN = "EXECUTION_UNCERTAIN"
    const val STATE_RECOVERY_REQUIRED = "RECOVERY_REQUIRED"
    const val STATE_SETTLED = "SETTLED"

    data class Record(
        val recordId: String,
        val commandId: String,
        val ackNonce: String,
        val ackTs: Long,
        val frameJson: String,
        val state: String,
        val createdAtMs: Long,
        val lastAttemptMs: Long = 0L,
        val attemptCount: Int = 0,
        val lastError: String? = null,
        val kind: String = KIND_ACK,
        val capacityUnits: Int = 1,
        val reservationId: String? = null,
        val replayCommitted: Boolean = false
    )

    sealed class EnqueueResult {
        data class Ok(val records: List<Record>) : EnqueueResult()
        data class Duplicate(val records: List<Record>) : EnqueueResult()
        data class Rejected(
            val code: String,
            val message: String,
            val records: List<Record>
        ) : EnqueueResult()
    }

    sealed class ReserveResult {
        data class Ok(val reservationId: String, val records: List<Record>) : ReserveResult()
        data class Rejected(val code: String, val message: String, val records: List<Record>) : ReserveResult()
    }

    fun recordIdFor(commandId: String, ackNonce: String): String =
        "${commandId.trim()}::${ackNonce.trim()}"

    fun leaseRecordId(reservationId: String): String = "lease::$reservationId"

    fun isWithinSkewWindow(ackTs: Long, nowSec: Long = System.currentTimeMillis() / 1000L): Boolean {
        if (ackTs <= 0L) return false
        return kotlin.math.abs(nowSec - ackTs) <= ACK_SKEW_SEC
    }

    fun occupiesCapacity(state: String): Boolean =
        when (state) {
            STATE_SETTLED, STATE_DELIVERED -> false
            else -> true
        }

    fun capacityUsed(records: List<Record>): Int =
        records.filter { occupiesCapacity(it.state) }.sumOf { it.capacityUnits.coerceAtLeast(1) }

    fun aggregateFrameChars(records: List<Record>): Int =
        records.filter { occupiesCapacity(it.state) }.sumOf { it.frameJson.length }

    fun freeCapacity(records: List<Record>): Int =
        (MAX_PENDING - capacityUsed(records)).coerceAtLeast(0)

    fun reserve(
        existing: List<Record>,
        commandId: String,
        units: Int = ACK_SLOTS_PER_COMMAND,
        nowMs: Long = System.currentTimeMillis(),
        reservationId: String = java.util.UUID.randomUUID().toString()
    ): ReserveResult {
        val cmd = commandId.trim()
        if (cmd.isEmpty()) {
            return ReserveResult.Rejected("missing_command_id", "commandId required", existing)
        }
        if (units <= 0) {
            return ReserveResult.Rejected("invalid_units", "units must be > 0", existing)
        }
        if (existing.any { it.kind == KIND_LEASE && it.commandId == cmd && occupiesCapacity(it.state) }) {
            return ReserveResult.Rejected(
                "lease_exists",
                "Active lease already exists for command",
                existing
            )
        }
        if (freeCapacity(existing) < units) {
            return ReserveResult.Rejected(
                "capacity_exhausted",
                "Cannot reserve $units units; free=${freeCapacity(existing)} used=${capacityUsed(existing)}",
                existing
            )
        }
        val lease = Record(
            recordId = leaseRecordId(reservationId),
            commandId = cmd,
            ackNonce = "",
            ackTs = 0L,
            frameJson = "",
            state = STATE_RESERVED,
            createdAtMs = nowMs,
            kind = KIND_LEASE,
            capacityUnits = units,
            reservationId = reservationId,
            replayCommitted = false
        )
        return ReserveResult.Ok(reservationId, existing + lease)
    }

    fun markReplayCommitted(existing: List<Record>, reservationId: String): List<Record> =
        existing.map {
            if (it.reservationId == reservationId && it.kind == KIND_LEASE)
                it.copy(replayCommitted = true)
            else it
        }

    fun markExecuting(existing: List<Record>, reservationId: String): List<Record> =
        existing.map {
            if (it.reservationId == reservationId && it.kind == KIND_LEASE &&
                (it.state == STATE_RESERVED || it.state == STATE_EXECUTING)
            ) {
                it.copy(state = STATE_EXECUTING, replayCommitted = true)
            } else it
        }

    fun markExecutionUncertain(
        existing: List<Record>,
        reservationId: String,
        error: String
    ): List<Record> =
        existing.map {
            if (it.reservationId == reservationId && it.kind == KIND_LEASE)
                it.copy(state = STATE_EXECUTION_UNCERTAIN, lastError = error, replayCommitted = true)
            else it
        }

    fun markRecoveryRequired(
        existing: List<Record>,
        reservationId: String,
        error: String
    ): List<Record> =
        existing.map {
            if (it.reservationId == reservationId && it.kind == KIND_LEASE)
                it.copy(state = STATE_RECOVERY_REQUIRED, lastError = error, replayCommitted = true)
            else it
        }

    /**
     * Safe cancel: only RESERVED leases without replay commitment.
     * Never releases EXECUTING / UNCERTAIN / RECOVERY / replay-committed leases.
     */
    fun releaseReservationIfSafe(
        existing: List<Record>,
        reservationId: String
    ): Pair<List<Record>, Boolean> {
        val lease = existing.firstOrNull {
            it.reservationId == reservationId && it.kind == KIND_LEASE
        } ?: return existing to false
        if (lease.state != STATE_RESERVED || lease.replayCommitted) {
            return existing to false
        }
        return existing.filterNot { it.recordId == lease.recordId } to true
    }

    fun releaseUnusedLeaseUnits(existing: List<Record>, reservationId: String): List<Record> {
        val lease = existing.firstOrNull {
            it.reservationId == reservationId && it.kind == KIND_LEASE && occupiesCapacity(it.state)
        } ?: return existing
        // Drop lease only when no longer EXECUTING/uncertain — terminal cleanup after acks attached.
        if (lease.state == STATE_EXECUTING || lease.state == STATE_RESERVED) {
            return existing.filterNot { it.recordId == lease.recordId }
        }
        return existing
    }

    /**
     * Persist a signed ack frame, consuming one unit from the command lease when present.
     */
    fun enqueue(
        existing: List<Record>,
        commandId: String,
        ackNonce: String,
        ackTs: Long,
        frameJson: String,
        nowMs: Long = System.currentTimeMillis(),
        reservationId: String? = null
    ): EnqueueResult {
        val id = recordIdFor(commandId, ackNonce)
        if (existing.any { it.recordId == id }) {
            return EnqueueResult.Duplicate(existing)
        }
        if (frameJson.isEmpty()) {
            return EnqueueResult.Rejected("empty_frame", "Ack frame empty", existing)
        }
        if (frameJson.length > MAX_FRAME_CHARS) {
            return EnqueueResult.Rejected(
                "frame_too_large",
                "Ack frame exceeds $MAX_FRAME_CHARS chars",
                existing
            )
        }

        var working = existing
        val lease = working.firstOrNull { rec ->
            rec.kind == KIND_LEASE &&
                occupiesCapacity(rec.state) &&
                (reservationId != null && rec.reservationId == reservationId ||
                    reservationId == null && rec.commandId == commandId.trim())
        }

        if (lease != null) {
            if (lease.capacityUnits <= 0) {
                return EnqueueResult.Rejected("lease_exhausted", "Lease has no remaining units", working)
            }
            // Consume one reserved unit: reduce lease, or remove lease at 0.
            working = working.map {
                if (it.recordId != lease.recordId) it
                else {
                    val left = it.capacityUnits - 1
                    if (left <= 0) it.copy(capacityUnits = 0, state = STATE_DELIVERED)
                    else it.copy(capacityUnits = left)
                }
            }.filterNot { it.kind == KIND_LEASE && it.capacityUnits <= 0 && it.state == STATE_DELIVERED }
        } else {
            if (freeCapacity(working) < 1) {
                return EnqueueResult.Rejected(
                    "capacity_exhausted",
                    "Pending ack queue at cap $MAX_PENDING; refusing new record (no silent discard)",
                    working
                )
            }
        }

        if (aggregateFrameChars(working) + frameJson.length > MAX_AGGREGATE_CHARS) {
            return EnqueueResult.Rejected(
                "aggregate_capacity_exhausted",
                "Pending ack aggregate exceeds $MAX_AGGREGATE_CHARS chars; refusing new record",
                working
            )
        }

        val deliveryState =
            if (isWithinSkewWindow(ackTs, nowMs / 1000L)) STATE_PENDING_DELIVERY else STATE_EXPIRED_RETAINED
        // Alias PENDING for deliverable() compatibility
        val state = if (deliveryState == STATE_PENDING_DELIVERY) STATE_PENDING else deliveryState

        val rec = Record(
            recordId = id,
            commandId = commandId.trim(),
            ackNonce = ackNonce.trim(),
            ackTs = ackTs,
            frameJson = frameJson,
            state = state,
            createdAtMs = nowMs,
            lastError = if (state == STATE_EXPIRED_RETAINED) "ack_ts_out_of_skew_at_enqueue" else null,
            kind = KIND_ACK,
            capacityUnits = 1,
            reservationId = lease?.reservationId ?: reservationId
        )
        return EnqueueResult.Ok(working + rec)
    }

    fun markSettled(existing: List<Record>, recordId: String): List<Record> =
        existing.filterNot { it.recordId == recordId }

    fun markExpiredRetained(
        existing: List<Record>,
        recordId: String,
        error: String,
        nowMs: Long = System.currentTimeMillis()
    ): List<Record> =
        existing.map {
            if (it.recordId != recordId) it
            else it.copy(
                state = STATE_EXPIRED_RETAINED,
                lastError = error,
                lastAttemptMs = nowMs,
                attemptCount = it.attemptCount + 1
            )
        }

    fun markAttempt(
        existing: List<Record>,
        recordId: String,
        error: String?,
        nowMs: Long = System.currentTimeMillis()
    ): List<Record> =
        existing.map {
            if (it.recordId != recordId) it
            else it.copy(
                lastError = error,
                lastAttemptMs = nowMs,
                attemptCount = it.attemptCount + 1
            )
        }

    fun reevaluateSkew(existing: List<Record>, nowSec: Long = System.currentTimeMillis() / 1000L): List<Record> =
        existing.map { rec ->
            if (rec.kind != KIND_ACK || rec.frameJson.isEmpty()) return@map rec
            when {
                rec.state == STATE_SETTLED || rec.state == STATE_DELIVERED -> rec
                isWithinSkewWindow(rec.ackTs, nowSec) && rec.state == STATE_EXPIRED_RETAINED ->
                    rec.copy(state = STATE_PENDING, lastError = null)
                !isWithinSkewWindow(rec.ackTs, nowSec) &&
                    (rec.state == STATE_PENDING || rec.state == STATE_PENDING_DELIVERY) ->
                    rec.copy(state = STATE_EXPIRED_RETAINED, lastError = "ack_ts_out_of_skew")
                else -> rec
            }
        }

    fun deliverable(existing: List<Record>, nowSec: Long = System.currentTimeMillis() / 1000L): List<Record> =
        reevaluateSkew(existing, nowSec).filter {
            it.kind == KIND_ACK && (it.state == STATE_PENDING || it.state == STATE_PENDING_DELIVERY)
        }

    fun retainedExpired(existing: List<Record>): List<Record> =
        existing.filter { it.state == STATE_EXPIRED_RETAINED }

    /**
     * Process-start reconciliation. Never auto-re-executes commands.
     * - RESERVED && !replayCommitted → safe release
     * - RESERVED && replayCommitted → RECOVERY_REQUIRED
     * - EXECUTING → EXECUTION_UNCERTAIN
     */
    fun reconcileAfterProcessStart(existing: List<Record>): List<Record> {
        val out = mutableListOf<Record>()
        for (rec in existing) {
            if (rec.kind != KIND_LEASE) {
                out.add(rec)
                continue
            }
            when {
                rec.state == STATE_RESERVED && !rec.replayCommitted -> {
                    // Safe cancel: execution never began and replay not consumed.
                }
                rec.state == STATE_RESERVED && rec.replayCommitted -> {
                    out.add(
                        rec.copy(
                            state = STATE_RECOVERY_REQUIRED,
                            lastError = "restart_after_replay_before_exec_mark"
                        )
                    )
                }
                rec.state == STATE_EXECUTING -> {
                    out.add(
                        rec.copy(
                            state = STATE_EXECUTION_UNCERTAIN,
                            lastError = "restart_during_or_after_exec_start"
                        )
                    )
                }
                else -> out.add(rec)
            }
        }
        return out
    }

    fun hasBlockingRecovery(records: List<Record>): Boolean =
        records.any {
            it.state == STATE_RECOVERY_REQUIRED ||
                it.state == STATE_EXECUTION_UNCERTAIN
        }
}
