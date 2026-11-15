package com.iips.launcher.convergence

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage 2A.3.7.1 / 2A.3.7.2 — durable Path A ack store.
 *
 * Envelope schemaVersion=2. Corruption never silently becomes an empty writable queue:
 * raw snapshot is preserved under a backup key and mutations are fenced.
 */
@Singleton
class PathAAckPendingStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "PathAAckPendingStore"
        const val PREFS_KEY = "path_a_ack_pending_v1"
        const val CORRUPT_BACKUP_KEY = "path_a_ack_pending_v1_corrupt_backup"
        const val CORRUPT_META_KEY = "path_a_ack_pending_v1_corrupt_meta"
        const val MIGRATION_FLAG = "path_a_ack_pending_migrated_from_telemetry_v1"
    }

    private val gson = Gson()
    private val lock = Any()

    sealed class LoadResult {
        data class Ok(val records: List<PathAAckPendingQueue.Record>) : LoadResult()
        data class Corrupt(
            val code: String,
            val rawPreserved: Boolean,
            /** Valid records recovered only when integrity of each was established. */
            val salvageableRecords: List<PathAAckPendingQueue.Record> = emptyList()
        ) : LoadResult()
    }

    fun loadResult(): LoadResult = synchronized(lock) {
        migrateAckFramesFromTelemetryQueueOnceUnlocked()
        if (isCorruptFlagSet()) {
            return LoadResult.Corrupt(
                code = readCorruptMetaCode() ?: "corrupt_flag",
                rawPreserved = !readCorruptBackup().isNullOrBlank()
            )
        }
        return when (val d = PathAAckEnvelope.decode(readRaw())) {
            is PathAAckEnvelope.DecodeResult.Ok -> LoadResult.Ok(d.records)
            is PathAAckEnvelope.DecodeResult.Corrupt -> {
                fenceCorruption(d.code, readRaw())
                LoadResult.Corrupt(d.code, rawPreserved = !readRaw().isNullOrBlank())
            }
        }
    }

    fun load(): List<PathAAckPendingQueue.Record> =
        when (val r = loadResult()) {
            is LoadResult.Ok -> r.records
            is LoadResult.Corrupt -> r.salvageableRecords
        }

    fun isRecoveryRequired(): Boolean = synchronized(lock) {
        if (isCorruptFlagSet()) return true
        when (val r = PathAAckEnvelope.decode(readRaw())) {
            is PathAAckEnvelope.DecodeResult.Ok ->
                PathAAckPendingQueue.hasBlockingRecovery(r.records)
            is PathAAckEnvelope.DecodeResult.Corrupt -> true
        }
    }

    fun isCorrupt(): Boolean = synchronized(lock) { isCorruptFlagSet() }

    fun save(records: List<PathAAckPendingQueue.Record>): Boolean = synchronized(lock) {
        if (isCorruptFlagSet()) {
            Log.e(TAG, "Refusing save — store is in corruption recovery fence")
            return false
        }
        return writeEnvelopeUnlocked(records)
    }

    fun enqueueExactFrame(
        frameJson: String,
        reservationId: String? = null
    ): PathAAckPendingQueue.EnqueueResult = synchronized(lock) {
        if (isCorruptFlagSet()) {
            return PathAAckPendingQueue.EnqueueResult.Rejected(
                "store_corrupt",
                "Ack store corruption recovery required",
                emptyList()
            )
        }
        val mapped = PathAAckHttpMapper.fromWsFrameJson(frameJson)
            ?: return PathAAckPendingQueue.EnqueueResult.Rejected(
                "unmappable_frame",
                "Frame is not a Path A ack",
                loadUnlockedOkOrEmpty()
            )
        val nonce = mapped.body["ack_nonce"]?.toString().orEmpty()
        val ts = (mapped.body["ack_ts"] as? Number)?.toLong()
            ?: mapped.body["ack_ts"]?.toString()?.toLongOrNull()
            ?: 0L
        val existing = loadUnlockedOkOrEmpty()
        val result = PathAAckPendingQueue.enqueue(
            existing = existing,
            commandId = mapped.commandId,
            ackNonce = nonce,
            ackTs = ts,
            frameJson = frameJson,
            reservationId = reservationId
        )
        when (result) {
            is PathAAckPendingQueue.EnqueueResult.Ok -> {
                if (!writeEnvelopeUnlocked(result.records)) {
                    return PathAAckPendingQueue.EnqueueResult.Rejected(
                        "persist_commit_failed",
                        "Encrypted prefs commit failed for ack enqueue",
                        existing
                    )
                }
            }
            is PathAAckPendingQueue.EnqueueResult.Duplicate -> Unit
            is PathAAckPendingQueue.EnqueueResult.Rejected -> {
                Log.e(TAG, "Ack enqueue rejected code=${result.code}: ${result.message}")
            }
        }
        return result
    }

    fun tryReserve(
        commandId: String,
        units: Int = PathAAckPendingQueue.ACK_SLOTS_PER_COMMAND
    ): PathAAckPendingQueue.ReserveResult = synchronized(lock) {
        if (isCorruptFlagSet()) {
            return PathAAckPendingQueue.ReserveResult.Rejected(
                "store_corrupt",
                "Cannot reserve — ack store corruption recovery required",
                emptyList()
            )
        }
        val existing = loadUnlockedOkOrEmpty()
        val result = PathAAckPendingQueue.reserve(existing, commandId, units)
        if (result is PathAAckPendingQueue.ReserveResult.Ok) {
            if (!writeEnvelopeUnlocked(result.records)) {
                return PathAAckPendingQueue.ReserveResult.Rejected(
                    "persist_commit_failed",
                    "Failed to commit reservation",
                    existing
                )
            }
        }
        return result
    }

    fun updateRecords(
        transform: (List<PathAAckPendingQueue.Record>) -> List<PathAAckPendingQueue.Record>
    ): Boolean = synchronized(lock) {
        if (isCorruptFlagSet()) return false
        val next = transform(loadUnlockedOkOrEmpty())
        return writeEnvelopeUnlocked(next)
    }

    fun markSettled(recordId: String): Boolean =
        updateRecords { PathAAckPendingQueue.markSettled(it, recordId) }

    fun markExpiredRetained(recordId: String, error: String): Boolean =
        updateRecords { PathAAckPendingQueue.markExpiredRetained(it, recordId, error) }

    fun markAttempt(recordId: String, error: String?): Boolean =
        updateRecords { PathAAckPendingQueue.markAttempt(it, recordId, error) }

    fun replaceAll(records: List<PathAAckPendingQueue.Record>): Boolean = save(records)

    fun reconcileAfterProcessStart(): Boolean = synchronized(lock) {
        if (isCorruptFlagSet()) return false
        val existing = loadUnlockedOkOrEmpty()
        val next = PathAAckPendingQueue.reconcileAfterProcessStart(existing)
        return writeEnvelopeUnlocked(next)
    }

    private fun loadUnlockedOkOrEmpty(): List<PathAAckPendingQueue.Record> =
        when (val r = PathAAckEnvelope.decode(readRaw())) {
            is PathAAckEnvelope.DecodeResult.Ok -> r.records
            is PathAAckEnvelope.DecodeResult.Corrupt -> {
                fenceCorruption(r.code, readRaw())
                emptyList()
            }
        }

    private fun writeEnvelopeUnlocked(records: List<PathAAckPendingQueue.Record>): Boolean {
        val json = PathAAckEnvelope.encode(records)
        val prefs = SecurePreferences.getEncryptedPrefs(context)
        val ok = prefs.edit().putString(PREFS_KEY, json).commit()
        if (!ok) Log.e(TAG, "Failed to commit Path A ack pending store")
        return ok
    }

    private fun fenceCorruption(code: String, raw: String?) {
        val prefs = SecurePreferences.getEncryptedPrefs(context)
        if (!raw.isNullOrBlank()) {
            val existingBackup = prefs.getString(CORRUPT_BACKUP_KEY, null)
            if (existingBackup.isNullOrBlank()) {
                prefs.edit().putString(CORRUPT_BACKUP_KEY, raw).commit()
            }
        }
        // Do NOT overwrite PREFS_KEY with empty array.
        val meta = JsonObject().apply {
            addProperty("code", code)
            addProperty("atMs", System.currentTimeMillis())
            addProperty("rawPreserved", !raw.isNullOrBlank())
        }
        prefs.edit()
            .putBoolean(CORRUPT_META_KEY + "_flag", true)
            .putString(CORRUPT_META_KEY, gson.toJson(meta))
            .commit()
        Log.e(TAG, "Ack store corruption fenced code=$code rawPreserved=${!raw.isNullOrBlank()}")
    }

    private fun isCorruptFlagSet(): Boolean =
        SecurePreferences.getEncryptedPrefs(context)
            .getBoolean(CORRUPT_META_KEY + "_flag", false)

    private fun readCorruptMetaCode(): String? = try {
        val raw = SecurePreferences.getEncryptedPrefs(context).getString(CORRUPT_META_KEY, null)
        if (raw.isNullOrBlank()) null
        else JsonParser.parseString(raw).asJsonObject.getAsJsonPrimitive("code")?.asString
    } catch (_: Exception) {
        null
    }

    private fun readCorruptBackup(): String? =
        SecurePreferences.getEncryptedPrefs(context).getString(CORRUPT_BACKUP_KEY, null)

    private fun migrateAckFramesFromTelemetryQueueOnceUnlocked() {
        val prefs = SecurePreferences.getEncryptedPrefs(context)
        if (prefs.getBoolean(MIGRATION_FLAG, false)) return
        if (isCorruptFlagSet()) return
        val telemetry = SecurePreferences.getOfflineTelemetryQueue(context)
        if (telemetry.isEmpty()) {
            prefs.edit().putBoolean(MIGRATION_FLAG, true).commit()
            return
        }
        val remainingTelemetry = mutableListOf<String>()
        var existing = when (val r = PathAAckEnvelope.decode(prefs.getString(PREFS_KEY, null))) {
            is PathAAckEnvelope.DecodeResult.Ok -> r.records
            is PathAAckEnvelope.DecodeResult.Corrupt -> {
                fenceCorruption(r.code, prefs.getString(PREFS_KEY, null))
                return
            }
        }
        var migrated = 0
        for (frame in telemetry) {
            val mapped = PathAAckHttpMapper.fromWsFrameJson(frame)
            if (mapped == null) {
                remainingTelemetry.add(frame)
                continue
            }
            val nonce = mapped.body["ack_nonce"]?.toString().orEmpty()
            val ts = (mapped.body["ack_ts"] as? Number)?.toLong()
                ?: mapped.body["ack_ts"]?.toString()?.toLongOrNull()
                ?: 0L
            when (
                val r = PathAAckPendingQueue.enqueue(
                    existing, mapped.commandId, nonce, ts, frame
                )
            ) {
                is PathAAckPendingQueue.EnqueueResult.Ok -> {
                    existing = r.records
                    migrated++
                }
                is PathAAckPendingQueue.EnqueueResult.Duplicate -> Unit
                is PathAAckPendingQueue.EnqueueResult.Rejected -> {
                    remainingTelemetry.add(frame)
                    Log.e(TAG, "Ack migration deferred: ${r.code}")
                }
            }
        }
        if (writeEnvelopeUnlocked(existing)) {
            SecurePreferences.setOfflineTelemetryQueue(context, remainingTelemetry)
            prefs.edit().putBoolean(MIGRATION_FLAG, true).commit()
            if (migrated > 0) {
                Log.i(TAG, "Migrated $migrated Path A ack frame(s) from telemetry queue")
            }
        }
    }

    private fun readRaw(): String? =
        SecurePreferences.getEncryptedPrefs(context).getString(PREFS_KEY, null)
}
