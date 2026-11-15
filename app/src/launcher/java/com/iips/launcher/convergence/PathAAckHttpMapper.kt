package com.iips.launcher.convergence

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Stage 2A.3.7 — map stored DoMdm ack frames to Quasar
 * `POST /device/commands/{id}/ack` body (AckDeviceCommandDto fields).
 *
 * Does not re-sign. Uses the exact proof/result/note already bound in the frame.
 */
object PathAAckHttpMapper {
    data class MappedAck(
        val commandId: String,
        /** Retrofit `@Body Map<String, Any>` — values must be non-null JSON-serializable. */
        val body: Map<String, Any>
    )

    fun fromWsFrameJson(frameJson: String): MappedAck? {
        return try {
            val root = JsonParser.parseString(frameJson).asJsonObject
            val type = root.getAsJsonPrimitive("type")?.asString?.trim().orEmpty()
            when {
                type.equals("command.ack", ignoreCase = true) -> mapCommandAck(root)
                type.equals("REQUEST_ACK", ignoreCase = true) -> mapRequestAck(root)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun mapCommandAck(root: JsonObject): MappedAck? {
        val data = root.getAsJsonObject("data") ?: return null
        val id = data.getAsJsonPrimitive("id")?.asString?.trim().orEmpty()
        if (id.isEmpty()) return null
        val status = data.getAsJsonPrimitive("status")?.asString?.trim().orEmpty()
        val proof = data.getAsJsonPrimitive("device_proof")?.asString
            ?: data.getAsJsonPrimitive("signature")?.asString
            ?: return null
        val nonce = data.getAsJsonPrimitive("ack_nonce")?.asString ?: return null
        val ts = data.getAsJsonPrimitive("ack_ts")?.asLong
            ?: data.getAsJsonPrimitive("ack_ts")?.asString?.toLongOrNull()
            ?: return null
        val body = linkedMapOf<String, Any>(
            "status" to status,
            "signature" to proof,
            "ack_nonce" to nonce,
            "ack_ts" to ts
        )
        if (data.has("note") && !data.get("note").isJsonNull) {
            val note = data.getAsJsonPrimitive("note")?.asString
            if (note != null) body["note"] = note
        }
        if (data.has("result") && data.get("result").isJsonObject) {
            body["result"] = gsonMap(data.getAsJsonObject("result"))
        }
        return MappedAck(commandId = id, body = body)
    }

    private fun mapRequestAck(root: JsonObject): MappedAck? {
        val data = root.getAsJsonObject("payload")
            ?: root.getAsJsonObject("data")
            ?: root
        val id = data.getAsJsonPrimitive("command_id")?.asString?.trim()
            ?: root.getAsJsonPrimitive("command_id")?.asString?.trim()
            ?: root.getAsJsonPrimitive("commandId")?.asString?.trim()
            ?: return null
        val proof = data.getAsJsonPrimitive("device_proof")?.asString
            ?: data.getAsJsonPrimitive("signature")?.asString
            ?: root.getAsJsonPrimitive("device_proof")?.asString
            ?: root.getAsJsonPrimitive("signature")?.asString
            ?: return null
        val nonce = data.getAsJsonPrimitive("ack_nonce")?.asString
            ?: root.getAsJsonPrimitive("ack_nonce")?.asString
            ?: return null
        val ts = data.getAsJsonPrimitive("ack_ts")?.asLong
            ?: data.getAsJsonPrimitive("ack_ts")?.asString?.toLongOrNull()
            ?: root.getAsJsonPrimitive("ack_ts")?.asLong
            ?: root.getAsJsonPrimitive("ack_ts")?.asString?.toLongOrNull()
            ?: return null
        val note = data.getAsJsonPrimitive("note")?.asString
            ?: root.getAsJsonPrimitive("note")?.asString
            ?: "request_ack_received"
        val resultEl = data.get("result") ?: data.get("execution_result")
        val body = linkedMapOf<String, Any>(
            "status" to "RECEIVED",
            "signature" to proof,
            "ack_nonce" to nonce,
            "ack_ts" to ts,
            "note" to note
        )
        if (resultEl != null && resultEl.isJsonObject) {
            body["result"] = gsonMap(resultEl.asJsonObject)
        } else if (resultEl != null && !resultEl.isJsonNull) {
            // Scalars already wrapped before sign on Android; keep object-only for HTTP DTO.
            return null
        }
        return MappedAck(commandId = id, body = body)
    }

    private fun gsonMap(obj: JsonObject): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        val raw = Gson().fromJson(obj, Map::class.java) as Map<String, Any?>
        return raw.filterValues { it != null }.mapValues { it.value as Any }
    }
}
