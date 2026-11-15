package com.iips.launcher.convergence

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Stage 2A.3.7.2 — versioned ack-store envelope encode/decode (pure).
 * Corrupt documents never decode as a writable empty Ok without a Corrupt result.
 */
object PathAAckEnvelope {
    private val gson = Gson()

    sealed class DecodeResult {
        data class Ok(val records: List<PathAAckPendingQueue.Record>) : DecodeResult()
        data class Corrupt(val code: String) : DecodeResult()
    }

    fun encode(records: List<PathAAckPendingQueue.Record>): String {
        val root = JsonObject()
        root.addProperty("schemaVersion", PathAAckPendingQueue.SCHEMA_VERSION)
        root.addProperty("integrity", "ok")
        val arr = JsonArray()
        for (r in records) {
            arr.add(encodeRecord(r))
        }
        root.add("records", arr)
        return gson.toJson(root)
    }

    fun decode(raw: String?): DecodeResult {
        if (raw.isNullOrBlank()) return DecodeResult.Ok(emptyList())
        return try {
            val el = JsonParser.parseString(raw)
            if (el.isJsonArray) {
                val records = decodeRecordArray(el.asJsonArray)
                    ?: return DecodeResult.Corrupt("legacy_array_invalid")
                return DecodeResult.Ok(records)
            }
            if (!el.isJsonObject) return DecodeResult.Corrupt("unexpected_root")
            val root = el.asJsonObject
            if (root.getAsJsonPrimitive("integrity")?.asString == "corrupt") {
                return DecodeResult.Corrupt(
                    root.getAsJsonPrimitive("corruptionCode")?.asString ?: "envelope_corrupt"
                )
            }
            val version = root.getAsJsonPrimitive("schemaVersion")?.asInt
            if (version != null && version > PathAAckPendingQueue.SCHEMA_VERSION) {
                return DecodeResult.Corrupt("unsupported_schema_version")
            }
            val recordsEl = root.get("records")
            if (recordsEl == null || !recordsEl.isJsonArray) {
                return DecodeResult.Corrupt("missing_records_array")
            }
            val records = decodeRecordArray(recordsEl.asJsonArray)
                ?: return DecodeResult.Corrupt("records_invalid")
            DecodeResult.Ok(records)
        } catch (_: Exception) {
            DecodeResult.Corrupt("json_parse")
        }
    }

    private fun encodeRecord(r: PathAAckPendingQueue.Record): JsonObject {
        val o = JsonObject()
        o.addProperty("recordId", r.recordId)
        o.addProperty("commandId", r.commandId)
        o.addProperty("ackNonce", r.ackNonce)
        o.addProperty("ackTs", r.ackTs)
        o.addProperty("frameJson", r.frameJson)
        o.addProperty("state", r.state)
        o.addProperty("createdAtMs", r.createdAtMs)
        o.addProperty("lastAttemptMs", r.lastAttemptMs)
        o.addProperty("attemptCount", r.attemptCount)
        if (r.lastError != null) o.addProperty("lastError", r.lastError)
        o.addProperty("kind", r.kind)
        o.addProperty("capacityUnits", r.capacityUnits)
        if (r.reservationId != null) o.addProperty("reservationId", r.reservationId)
        o.addProperty("replayCommitted", r.replayCommitted)
        return o
    }

    private fun decodeRecordArray(arr: JsonArray): List<PathAAckPendingQueue.Record>? {
        val out = ArrayList<PathAAckPendingQueue.Record>(arr.size())
        val seenIds = HashSet<String>()
        val seenNonces = HashSet<String>()
        for (item in arr) {
            if (!item.isJsonObject) return null
            val o = item.asJsonObject
            val recordId = o.getAsJsonPrimitive("recordId")?.asString?.trim().orEmpty()
            val commandId = o.getAsJsonPrimitive("commandId")?.asString?.trim().orEmpty()
            val state = o.getAsJsonPrimitive("state")?.asString?.trim().orEmpty()
            val kind = o.getAsJsonPrimitive("kind")?.asString ?: PathAAckPendingQueue.KIND_ACK
            if (recordId.isEmpty() || commandId.isEmpty() || state.isEmpty()) return null
            if (!seenIds.add(recordId)) return null
            val ackNonce = o.getAsJsonPrimitive("ackNonce")?.asString.orEmpty()
            val frameJson = o.getAsJsonPrimitive("frameJson")?.asString.orEmpty()
            if (kind == PathAAckPendingQueue.KIND_ACK) {
                if (frameJson.isEmpty() || ackNonce.isEmpty()) return null
                val nonceKey = "$commandId::$ackNonce"
                if (!seenNonces.add(nonceKey)) return null
            }
            out.add(
                PathAAckPendingQueue.Record(
                    recordId = recordId,
                    commandId = commandId,
                    ackNonce = ackNonce,
                    ackTs = o.getAsJsonPrimitive("ackTs")?.asLong ?: 0L,
                    frameJson = frameJson,
                    state = state,
                    createdAtMs = o.getAsJsonPrimitive("createdAtMs")?.asLong ?: 0L,
                    lastAttemptMs = o.getAsJsonPrimitive("lastAttemptMs")?.asLong ?: 0L,
                    attemptCount = o.getAsJsonPrimitive("attemptCount")?.asInt ?: 0,
                    lastError = o.getAsJsonPrimitive("lastError")?.asString,
                    kind = kind,
                    capacityUnits = o.getAsJsonPrimitive("capacityUnits")?.asInt ?: 1,
                    reservationId = o.getAsJsonPrimitive("reservationId")?.asString,
                    replayCommitted = o.getAsJsonPrimitive("replayCommitted")?.asBoolean ?: false
                )
            )
        }
        return out
    }
}
