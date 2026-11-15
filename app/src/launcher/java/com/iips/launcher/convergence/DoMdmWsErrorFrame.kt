package com.iips.launcher.convergence

import com.google.gson.JsonObject

/**
 * Stage 2A.3.6 — parse Quasar DoMdm raw WebSocket `type=error` frames.
 *
 * Server contract (DoMdm, not Socket.IO):
 * `{ "type":"error", "request_id"?: string, "data":{ "message": string } }`
 *
 * Classification is local and conservative: permanent auth/proof failures are
 * never marked retryable. Messages are treated as opaque strings (no key/stack
 * leakage expected after Quasar sanitizeDoMdmWsErrorMessage).
 */
object DoMdmWsErrorFrame {
    data class Parsed(
        val requestId: String?,
        val message: String,
        val code: String,
        val retryable: Boolean
    )

    fun parse(root: JsonObject): Parsed? {
        val type = root.getAsJsonPrimitive("type")?.asString?.trim()?.lowercase()
        if (type != "error") return null
        val requestId = root.getAsJsonPrimitive("request_id")?.asString
            ?: root.getAsJsonPrimitive("requestId")?.asString
        val data = root.getAsJsonObject("data")
        val message = data?.getAsJsonPrimitive("message")?.asString?.trim().orEmpty()
        if (message.isEmpty()) {
            return Parsed(
                requestId = requestId,
                message = "error",
                code = "ack_rejected",
                retryable = false
            )
        }
        val classified = classify(message)
        return Parsed(
            requestId = requestId,
            message = message,
            code = classified.first,
            retryable = classified.second
        )
    }

    /**
     * @return Pair(code, retryable)
     */
    fun classify(message: String): Pair<String, Boolean> {
        val msg = message.lowercase()
        return when {
            msg.contains("unauthorized") || msg.contains("session not bound") ->
                "unauthorized_socket" to false
            msg.contains("not found") ->
                "command_not_found" to false
            msg.contains("nonce") || msg.contains("replay") ->
                "ack_nonce_replay" to false
            msg.contains("skew") ->
                "ack_ts_out_of_skew" to true
            msg.contains("proof") || msg.contains("acknowledgement proof") ->
                "invalid_device_proof" to false
            msg.contains("payload rejected") || msg.contains("invalid status") ->
                "invalid_ack_payload" to false
            msg.contains("forbidden") || msg.contains("revoked") ->
                "forbidden" to false
            msg.contains("expired") ->
                "command_expired" to false
            msg.contains("unknown message type") ->
                "protocol_error" to false
            msg.contains("temporary") || msg.contains("transport") ->
                "ack_transport_error" to true
            else ->
                "ack_rejected" to false
        }
    }
}
