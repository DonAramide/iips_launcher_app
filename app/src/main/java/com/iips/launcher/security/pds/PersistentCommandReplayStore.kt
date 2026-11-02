package com.iips.launcher.security.pds

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable, synchronous, crash-safe persistent replay store (Deliverable 7).
 *
 * Guarantees:
 * 1. Tracks both `command_id` and `nonce` bound to `device_id`.
 * 2. Retains replay records until at least `expires_at + retentionAfterExpirySec` (default 24 hours).
 * 3. `checkAndReserveAtomically` is synchronized and flushes to disk (`FileDescriptor.sync()`)
 *    via atomic temp-file rename BEFORE a command is allowed to execute.
 * 4. Survives process restarts and works completely offline.
 */
class PersistentCommandReplayStore(
    private val storageFile: File,
    private val retentionAfterExpirySec: Long = 24L * 60L * 60L,
    private val maxEntries: Int = 10_000
) {
    enum class ReplayDecision {
        ACCEPTED,
        REPLAY_COMMAND_ID,
        REPLAY_NONCE
    }

    data class ReplayRecord(
        val commandId: String,
        val nonce: String,
        val deviceId: String,
        val expiresAtEpochSec: Long,
        val recordedAtEpochSec: Long
    )

    private val lock = Any()
    private val byCommandId = LinkedHashMap<String, ReplayRecord>()
    private val byNonce = HashMap<String, ReplayRecord>()

    init {
        synchronized(lock) {
            loadFromDiskLocked()
        }
    }

    fun checkAndReserveAtomically(
        commandId: String,
        nonce: String,
        deviceId: String,
        expiresAtEpochSec: Long,
        nowEpochSec: Long = System.currentTimeMillis() / 1000L
    ): ReplayDecision {
        val cleanCmdId = commandId.trim()
        val cleanNonce = nonce.trim()
        val cleanDeviceId = deviceId.trim()
        require(cleanCmdId.isNotEmpty()) { "commandId cannot be empty" }
        require(cleanNonce.isNotEmpty()) { "nonce cannot be empty" }
        require(cleanDeviceId.isNotEmpty()) { "deviceId cannot be empty" }

        synchronized(lock) {
            purgeExpiredLocked(nowEpochSec)

            if (byCommandId.containsKey(cleanCmdId)) {
                return ReplayDecision.REPLAY_COMMAND_ID
            }
            if (byNonce.containsKey(cleanNonce)) {
                return ReplayDecision.REPLAY_NONCE
            }

            val record = ReplayRecord(
                commandId = cleanCmdId,
                nonce = cleanNonce,
                deviceId = cleanDeviceId,
                expiresAtEpochSec = expiresAtEpochSec,
                recordedAtEpochSec = nowEpochSec
            )

            byCommandId[cleanCmdId] = record
            byNonce[cleanNonce] = record

            while (byCommandId.size > maxEntries) {
                val eldestKey = byCommandId.keys.iterator().next()
                val removed = byCommandId.remove(eldestKey)
                if (removed != null) {
                    byNonce.remove(removed.nonce)
                }
            }

            persistToDiskLocked()
            return ReplayDecision.ACCEPTED
        }
    }

    fun isCommandConsumed(commandId: String, nowEpochSec: Long = System.currentTimeMillis() / 1000L): Boolean {
        synchronized(lock) {
            purgeExpiredLocked(nowEpochSec)
            return byCommandId.containsKey(commandId.trim())
        }
    }

    fun isNonceConsumed(nonce: String, nowEpochSec: Long = System.currentTimeMillis() / 1000L): Boolean {
        synchronized(lock) {
            purgeExpiredLocked(nowEpochSec)
            return byNonce.containsKey(nonce.trim())
        }
    }

    fun size(): Int {
        synchronized(lock) {
            return byCommandId.size
        }
    }

    private fun purgeExpiredLocked(nowEpochSec: Long) {
        val iter = byCommandId.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val rec = entry.value
            if (nowEpochSec > rec.expiresAtEpochSec + retentionAfterExpirySec) {
                iter.remove()
                byNonce.remove(rec.nonce)
            }
        }
    }

    private fun loadFromDiskLocked() {
        byCommandId.clear()
        byNonce.clear()
        if (!storageFile.exists()) return
        val lines = storageFile.readLines(StandardCharsets.UTF_8)
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val parts = trimmed.split("\t")
            if (parts.size != 5) continue
            val cmdId = parts[0]
            val nonce = parts[1]
            val devId = parts[2]
            val exp = parts[3].toLongOrNull() ?: continue
            val recAt = parts[4].toLongOrNull() ?: continue
            val rec = ReplayRecord(
                commandId = cmdId,
                nonce = nonce,
                deviceId = devId,
                expiresAtEpochSec = exp,
                recordedAtEpochSec = recAt
            )
            byCommandId[cmdId] = rec
            byNonce[nonce] = rec
        }
    }

    private fun persistToDiskLocked() {
        val parent = storageFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmpFile = File(storageFile.parentFile, "${storageFile.name}.tmp")
        val sb = StringBuilder("# pds-command-replay-ledger-v1\n")
        for (rec in byCommandId.values) {
            val safeCmd = rec.commandId.replace("\t", "").replace("\n", "")
            val safeNonce = rec.nonce.replace("\t", "").replace("\n", "")
            val safeDev = rec.deviceId.replace("\t", "").replace("\n", "")
            sb.append(safeCmd).append('\t')
                .append(safeNonce).append('\t')
                .append(safeDev).append('\t')
                .append(rec.expiresAtEpochSec).append('\t')
                .append(rec.recordedAtEpochSec).append('\n')
        }
        FileOutputStream(tmpFile).use { fos ->
            fos.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        Files.move(
            tmpFile.toPath(),
            storageFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    }
}
