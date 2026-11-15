package com.iips.launcher.security.pds

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Phase 6.5.5.1 Stage 2A.2 — canonical command acknowledgement transcript v2
 * with SHA-256 digests of canonical result + note.
 *
 * Telemetry HMAC must never authenticate command acks. Issuer command JWS must never
 * be echoed as proof of execution. The enrolled PDS device key signs a fresh transcript.
 */
object CommandAckProof {
    const val HEADER = "dotroid-cmd-ack-v2"
    const val HEADER_V1_REJECTED = "dotroid-cmd-ack-v1"

    private const val ABSENT = "\u0000ABSENT"
    private const val NULL_MARK = "\u0000NULL"

    data class AckProofMaterial(
        val status: String,
        val ackNonce: String,
        val ackTs: Long,
        val deviceProofB64Url: String,
        val transcriptUtf8: String,
        val resultSha256: String,
        val noteSha256: String
    )

    fun normalizeStatus(raw: String): String {
        val upper = raw.trim().uppercase()
        return when (upper) {
            "REJECTED", "DENIED", "ERROR" -> "FAILED"
            "RECEIVED", "EXECUTING", "SUCCESS", "FAILED" -> upper
            else -> "FAILED"
        }
    }

    /**
     * JCS-like canonical JSON: sorted object keys, no whitespace, UTF-8.
     * Numbers: finite JSON numbers; integral values rendered without decimal (matches JS JSON.stringify for ints).
     */
    fun canonicalJson(value: Any?): String {
        return when (value) {
            null -> "null"
            is Boolean -> if (value) "true" else "false"
            is Number -> canonicalNumber(value)
            is String -> jsonQuote(value)
            is Map<*, *> -> {
                val sortedKeys = value.keys.map { it.toString() }.sorted()
                val parts = ArrayList<String>(sortedKeys.size)
                for (k in sortedKeys) {
                    val v = value.entries.first { it.key.toString() == k }.value
                    if (v === Unit) continue
                    parts.add("${jsonQuote(k)}:${canonicalJson(v)}")
                }
                "{${parts.joinToString(",")}}"
            }
            is List<*> -> "[${value.joinToString(",") { canonicalJson(it) }}]"
            else -> throw IllegalArgumentException("canonical_json_unsupported_type:${value::class.java.name}")
        }
    }

    private fun canonicalNumber(value: Number): String {
        val d = value.toDouble()
        require(d.isFinite()) { "canonical_json_non_finite_number" }
        return if (value is Int || value is Long || value is Short || value is Byte) {
            value.toLong().toString()
        } else if (d == d.toLong().toDouble()) {
            d.toLong().toString()
        } else {
            // Match JSON.stringify for common doubles (no scientific for typical values).
            java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()
        }
    }

    private fun jsonQuote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u%04x".format(ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun sha256Hex(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    fun hashAckResultAbsent(): String = sha256Hex(ABSENT)

    fun hashAckResultNull(): String = sha256Hex(NULL_MARK)

    fun hashAckResult(result: Map<String, Any?>): String =
        sha256Hex("\u0000result:json\n${canonicalJson(result)}")

    fun hashAckNoteAbsent(): String = sha256Hex(ABSENT)

    fun hashAckNoteNull(): String = sha256Hex(NULL_MARK)

    fun hashAckNote(note: String): String = sha256Hex("\u0000note:str\n$note")

    fun buildTranscript(
        commandId: String,
        deviceId: String,
        status: String,
        ackNonce: String,
        ackTs: Long,
        resultSha256: String,
        noteSha256: String
    ): ByteArray {
        val normalized = normalizeStatus(status)
        val text = listOf(
            HEADER,
            "command_id=${commandId.trim()}",
            "device_id=${deviceId.trim()}",
            "status=$normalized",
            "ack_nonce=${ackNonce.trim()}",
            "ack_ts=$ackTs",
            "result_sha256=${resultSha256.trim().lowercase()}",
            "note_sha256=${noteSha256.trim().lowercase()}"
        ).joinToString("\n")
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    fun create(
        signer: PdsDeviceKeySigner,
        commandId: String,
        deviceId: String,
        status: String,
        result: Map<String, Any?> = emptyMap(),
        note: String = "",
        resultAbsent: Boolean = false,
        resultNull: Boolean = false,
        noteAbsent: Boolean = false,
        noteNull: Boolean = false,
        ackNonce: String = UUID.randomUUID().toString(),
        ackTs: Long = System.currentTimeMillis() / 1000L
    ): AckProofMaterial {
        val normalized = normalizeStatus(status)
        val resultSha = when {
            resultAbsent -> hashAckResultAbsent()
            resultNull -> hashAckResultNull()
            else -> hashAckResult(result)
        }
        val noteSha = when {
            noteAbsent -> hashAckNoteAbsent()
            noteNull -> hashAckNoteNull()
            else -> hashAckNote(note)
        }
        val transcriptBytes = buildTranscript(
            commandId, deviceId, normalized, ackNonce, ackTs, resultSha, noteSha
        )
        val proof = signer.signPayload(transcriptBytes)
        return AckProofMaterial(
            status = normalized,
            ackNonce = ackNonce,
            ackTs = ackTs,
            deviceProofB64Url = proof,
            transcriptUtf8 = String(transcriptBytes, StandardCharsets.UTF_8),
            resultSha256 = resultSha,
            noteSha256 = noteSha
        )
    }

    /**
     * Stage 2A.3 execution-result contract (Android → Quasar Path A):
     *
     * Quasar `AckDeviceCommandDto.result` accepts only a JSON object, `null`, or
     * absent. Handlers SHOULD return Maps. Scalars and lists are wrapped as
     * `{"value": …}` so the signed object equals the transmitted object.
     *
     * Callers MUST pass the returned Map into both [create] and the wire payload.
     */
    fun resultObjectFrom(value: Any?): Map<String, Any?> {
        return when (value) {
            null -> mapOf("value" to null)
            is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to v }
            else -> mapOf("value" to value)
        }
    }
}
