package com.iips.launcher.convergence

import android.content.Context
import android.util.Log
import com.iips.launcher.network.ConfigService
import com.iips.launcher.storage.SecurePreferences
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage 2A.3.7 / 2A.3.7.1 / 2A.3.7.2 — Path A acknowledgement delivery.
 *
 * Persist-before-delivery; WS non-authoritative; HTTP settle authoritative.
 * Enqueue consumes pre-execution capacity reservation when [reservationId] is provided.
 */
@Singleton
class PathAAckDelivery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configService: ConfigService,
    private val connectionManager: Lazy<DotroidRuntimeConnectionManager>,
    private val lifecycle: PathAAckLifecycle
) {
    companion object {
        private const val TAG = "PathAAckDelivery"
    }

    private val mutex = Mutex()
    private val pendingStore get() = lifecycle.pendingStore()

    suspend fun deliverAckFrame(
        frameJson: String,
        reservationId: String? = null
    ): Boolean = mutex.withLock {
        val enqueued = lifecycle.enqueueSignedFrame(frameJson, reservationId)
        val recordId = when (enqueued) {
            is PathAAckPendingQueue.EnqueueResult.Ok,
            is PathAAckPendingQueue.EnqueueResult.Duplicate -> {
                val mapped = PathAAckHttpMapper.fromWsFrameJson(frameJson) ?: return false
                PathAAckPendingQueue.recordIdFor(
                    mapped.commandId,
                    mapped.body["ack_nonce"].toString()
                )
            }
            is PathAAckPendingQueue.EnqueueResult.Rejected -> {
                Log.e(
                    TAG,
                    "Cannot durable-persist ack (${enqueued.code}): ${enqueued.message}"
                )
                return false
            }
        }

        val mgr = connectionManager.get()
        if (mgr.isConnected) {
            try {
                mgr.transmitFrame(frameJson)
            } catch (e: Exception) {
                Log.w(TAG, "WS ack notify failed (non-authoritative): ${e.message}")
            }
        }

        return settleViaHttp(recordId, frameJson)
    }

    suspend fun flushPendingAcks(): Int = mutex.withLock {
        if (pendingStore.isCorrupt()) {
            Log.e(TAG, "Flush skipped — ack store corruption recovery required")
            return 0
        }
        val reevaluated = PathAAckPendingQueue.reevaluateSkew(pendingStore.load())
        pendingStore.replaceAll(reevaluated)
        var settled = 0
        val mgr = connectionManager.get()
        for (rec in PathAAckPendingQueue.deliverable(reevaluated)) {
            if (mgr.isConnected) {
                try {
                    mgr.transmitFrame(rec.frameJson)
                } catch (_: Exception) {
                    // non-authoritative
                }
            }
            if (settleViaHttp(rec.recordId, rec.frameJson)) {
                settled++
            }
        }
        val after = pendingStore.load()
        val expired = PathAAckPendingQueue.retainedExpired(after).size
        if (settled > 0 || expired > 0) {
            Log.i(
                TAG,
                "Path A ack flush: settled=$settled expired_retained=$expired " +
                    "pending=${PathAAckPendingQueue.deliverable(after).size}"
            )
        }
        return settled
    }

    private suspend fun settleViaHttp(recordId: String, frameJson: String): Boolean {
        val mapped = PathAAckHttpMapper.fromWsFrameJson(frameJson) ?: return false
        val ackTs = (mapped.body["ack_ts"] as? Number)?.toLong()
            ?: mapped.body["ack_ts"]?.toString()?.toLongOrNull()
            ?: 0L

        if (!PathAAckPendingQueue.isWithinSkewWindow(ackTs)) {
            pendingStore.markExpiredRetained(recordId, "ack_ts_out_of_skew")
            Log.w(
                TAG,
                "Ack retained expired (skew) command=${mapped.commandId} record=$recordId — no re-sign"
            )
            return false
        }

        val token = SecurePreferences.getDeviceToken(context)
        if (token.isNullOrBlank()) {
            pendingStore.markAttempt(recordId, "missing_device_jwt")
            return false
        }

        return try {
            val resp = configService.ackPathACommand(
                authHeader = "Bearer $token",
                commandId = mapped.commandId,
                body = mapped.body
            )
            val classified = PathAHttpErrorClassifier.classify(
                httpStatus = resp.code(),
                message = resp.errorBody()?.string() ?: resp.message()
            )
            when {
                resp.isSuccessful || classified.code == "ack_nonce_replay" -> {
                    pendingStore.markSettled(recordId)
                    Log.i(
                        TAG,
                        "HTTP Path A ack settled command=${mapped.commandId} " +
                            "http=${resp.code()} code=${classified.code}"
                    )
                    true
                }
                classified.code == "ack_ts_out_of_skew" -> {
                    pendingStore.markExpiredRetained(recordId, classified.code)
                    Log.w(TAG, "Server skew — retained exact proof command=${mapped.commandId}")
                    false
                }
                else -> {
                    pendingStore.markAttempt(recordId, classified.code)
                    Log.w(
                        TAG,
                        "HTTP Path A ack failed command=${mapped.commandId} " +
                            "code=${classified.code} retryable=${classified.retryable} http=${resp.code()}"
                    )
                    false
                }
            }
        } catch (e: Exception) {
            val classified = PathAHttpErrorClassifier.classify(0, e.message)
            pendingStore.markAttempt(recordId, classified.code)
            Log.e(TAG, "HTTP Path A ack transport error (${classified.code}): ${e.message}")
            false
        }
    }
}
