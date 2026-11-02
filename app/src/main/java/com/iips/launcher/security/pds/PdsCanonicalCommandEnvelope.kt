package com.iips.launcher.security.pds

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.TreeMap

object PdsCanonicalCommandEnvelope {
    const val COMMAND_PROTOCOL_VERSION = 1
    const val COMMAND_SIGNATURE_ALG = "ES256"
    const val COMMAND_JWS_TYP = "dotroid-cmd+jws"
    const val KEYSET_JWS_TYP = "dotroid-keyset+jws"

    const val MAX_STANDARD_TTL_SECONDS = 24L * 60L * 60L
    const val MAX_SENSITIVE_TTL_SECONDS = 15L * 60L

    val SENSITIVE_SHORT_TTL_COMMANDS: Set<String> = setOf(
        "FACTORY_RESET",
        "INSTALL_APK",
        "UNINSTALL_APP"
    )

    val APPROVAL_REQUIRED_COMMANDS: Set<String> = setOf(
        "FACTORY_RESET",
        "INSTALL_APK",
        "UNINSTALL_APP"
    )

    private val KEY_ID_REGEX = Regex("^(cmd|mfs|root)-[a-z0-9][a-z0-9._-]{1,60}$")
    private val COMMAND_TYPE_REGEX = Regex("^[A-Z][A-Z0-9_]{1,63}$")
    private val BASE64URL_REGEX = Regex("^[A-Za-z0-9_-]+$")
    private val ISO_UTC_SECONDS_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")

    private val REQUIRED_CLAIM_KEYS = listOf(
        "alg",
        "approval_ref",
        "command_id",
        "device_id",
        "device_identity_ref",
        "expires_at",
        "issued_at",
        "issuer",
        "key_id",
        "nonce",
        "not_before",
        "payload",
        "protocol_version",
        "tenant_id",
        "type"
    )

    data class CanonicalProtectedHeader(
        val alg: String,
        val kid: String,
        val typ: String,
        val v: Long
    )

    data class CanonicalCommandClaims(
        val alg: String,
        val approvalRef: String?,
        val commandId: String,
        val deviceId: String,
        val deviceIdentityRef: String,
        val expiresAt: String,
        val issuedAt: String,
        val issuer: String,
        val keyId: String,
        val nonce: String,
        val notBefore: String,
        val payload: Map<String, Any?>,
        val protocolVersion: Long,
        val tenantId: String,
        val type: String
    ) {
        fun toCanonicalMap(): Map<String, Any?> {
            val map = TreeMap<String, Any?>()
            map["alg"] = alg
            map["approval_ref"] = approvalRef
            map["command_id"] = commandId
            map["device_id"] = deviceId
            map["device_identity_ref"] = deviceIdentityRef
            map["expires_at"] = expiresAt
            map["issued_at"] = issuedAt
            map["issuer"] = issuer
            map["key_id"] = keyId
            map["nonce"] = nonce
            map["not_before"] = notBefore
            map["payload"] = payload
            map["protocol_version"] = protocolVersion
            map["tenant_id"] = tenantId
            map["type"] = type
            return map
        }
    }

    data class ParsedCompactCommandJws(
        val header: CanonicalProtectedHeader,
        val claims: CanonicalCommandClaims,
        val signatureBytes: ByteArray,
        val signatureB64: String,
        val signingInputBytes: ByteArray
    )

    fun toBase64Url(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun fromBase64UrlStrict(input: String, fieldName: String): ByteArray {
        if (input.isEmpty() || !BASE64URL_REGEX.matches(input) || input.length % 4 == 1) {
            throw IllegalArgumentException("Invalid base64url encoding in $fieldName")
        }
        val decoded = try {
            Base64.getUrlDecoder().decode(input)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid base64url encoding in $fieldName", e)
        }
        if (toBase64Url(decoded) != input) {
            throw IllegalArgumentException("Non-canonical base64url encoding in $fieldName")
        }
        return decoded
    }

    fun parseUtcIsoSeconds(iso: String, fieldName: String): Long {
        if (!ISO_UTC_SECONDS_REGEX.matches(iso)) {
            throw IllegalArgumentException(
                "$fieldName must be strict UTC ISO-8601 without fractional seconds (YYYY-MM-DDTHH:mm:ssZ)"
            )
        }
        val instant = try {
            Instant.parse(iso)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid timestamp in $fieldName", e)
        }
        val formatted = DateTimeFormatter.ISO_INSTANT.format(instant)
        if (formatted != iso) {
            throw IllegalArgumentException("Out-of-range calendar date in $fieldName")
        }
        return instant.epochSecond
    }

    /**
     * Strict JSON parser that rejects duplicate object keys at any nesting level,
     * floating-point numbers, trailing commas, and trailing characters.
     */
    fun parseJsonRejectingDuplicateKeys(jsonText: String): Any? {
        if (jsonText.isEmpty()) {
            throw IllegalArgumentException("JSON input must be a non-empty string")
        }
        var idx = 0
        val len = jsonText.length

        fun skipWs() {
            while (idx < len) {
                val c = jsonText[idx]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    idx++
                } else {
                    break
                }
            }
        }

        fun parseString(): String {
            if (idx >= len || jsonText[idx] != '"') {
                throw IllegalArgumentException("Expected '\"' at position $idx")
            }
            idx++ // skip opening quote
            val sb = StringBuilder()
            while (idx < len) {
                val ch = jsonText[idx++]
                if (ch == '"') {
                    return sb.toString()
                }
                if (ch == '\\') {
                    if (idx >= len) throw IllegalArgumentException("Unterminated escape in JSON string")
                    when (val esc = jsonText[idx++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (idx + 4 > len) {
                                throw IllegalArgumentException("Invalid unicode escape in JSON string")
                            }
                            val hex = jsonText.substring(idx, idx + 4)
                            val code = hex.toIntOrNull(16)
                                ?: throw IllegalArgumentException("Invalid unicode hex in JSON string: $hex")
                            sb.append(code.toChar())
                            idx += 4
                        }
                        else -> throw IllegalArgumentException("Invalid escape sequence \\$esc")
                    }
                } else {
                    if (ch.code < 0x20) {
                        throw IllegalArgumentException("Unescaped control character in JSON string")
                    }
                    sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        fun parseNumber(): Long {
            val start = idx
            if (jsonText[idx] == '-') idx++
            if (idx >= len) throw IllegalArgumentException("Malformed JSON number")
            if (jsonText[idx] == '0') {
                idx++
            } else if (jsonText[idx] in '1'..'9') {
                while (idx < len && jsonText[idx] in '0'..'9') {
                    idx++
                }
            } else {
                throw IllegalArgumentException("Invalid JSON number at position $start")
            }
            if (idx < len && (jsonText[idx] == '.' || jsonText[idx] == 'e' || jsonText[idx] == 'E')) {
                throw IllegalArgumentException(
                    "Floating-point or exponential numbers are prohibited in canonical command JSON (at position $start)"
                )
            }
            val raw = jsonText.substring(start, idx)
            if (raw == "-0") {
                throw IllegalArgumentException("Negative zero is prohibited in canonical JSON")
            }
            val num = raw.toLongOrNull()
                ?: throw IllegalArgumentException("Integer out of range in canonical JSON: $raw")
            if (num < -9007199254740991L || num > 9007199254740991L) {
                throw IllegalArgumentException("Integer out of IEEE-754 safe range: $raw")
            }
            return num
        }

        lateinit var parseValueRef: () -> Any?

        fun parseArray(): List<Any?> {
            idx++ // skip '['
            skipWs()
            val list = mutableListOf<Any?>()
            if (idx < len && jsonText[idx] == ']') {
                idx++
                return list
            }
            while (true) {
                list.add(parseValueRef())
                skipWs()
                if (idx < len && jsonText[idx] == ',') {
                    idx++
                    skipWs()
                    if (idx < len && jsonText[idx] == ']') {
                        throw IllegalArgumentException("Trailing comma in JSON array")
                    }
                    continue
                }
                if (idx < len && jsonText[idx] == ']') {
                    idx++
                    return list
                }
                throw IllegalArgumentException("Expected ',' or ']' in array at position $idx")
            }
        }

        fun parseObject(): Map<String, Any?> {
            idx++ // skip '{'
            skipWs()
            val map = TreeMap<String, Any?>()
            val seen = HashSet<String>()
            if (idx < len && jsonText[idx] == '}') {
                idx++
                return map
            }
            while (true) {
                skipWs()
                val key = parseString()
                if (!seen.add(key)) {
                    throw IllegalArgumentException("Duplicate JSON key prohibited: \"$key\"")
                }
                skipWs()
                if (idx >= len || jsonText[idx] != ':') {
                    throw IllegalArgumentException("Expected ':' after key \"$key\" at position $idx")
                }
                idx++
                map[key] = parseValueRef()
                skipWs()
                if (idx < len && jsonText[idx] == ',') {
                    idx++
                    skipWs()
                    if (idx < len && jsonText[idx] == '}') {
                        throw IllegalArgumentException("Trailing comma in JSON object")
                    }
                    continue
                }
                if (idx < len && jsonText[idx] == '}') {
                    idx++
                    return map
                }
                throw IllegalArgumentException("Expected ',' or '}' in object at position $idx")
            }
        }

        parseValueRef = {
            skipWs()
            if (idx >= len) throw IllegalArgumentException("Unexpected end of JSON input")
            when (val ch = jsonText[idx]) {
                '"' -> parseString()
                '{' -> parseObject()
                '[' -> parseArray()
                '-', in '0'..'9' -> parseNumber()
                't' -> {
                    if (jsonText.startsWith("true", idx)) {
                        idx += 4
                        true
                    } else {
                        throw IllegalArgumentException("Unexpected token at position $idx")
                    }
                }
                'f' -> {
                    if (jsonText.startsWith("false", idx)) {
                        idx += 5
                        false
                    } else {
                        throw IllegalArgumentException("Unexpected token at position $idx")
                    }
                }
                'n' -> {
                    if (jsonText.startsWith("null", idx)) {
                        idx += 4
                        null
                    } else {
                        throw IllegalArgumentException("Unexpected token at position $idx")
                    }
                }
                else -> throw IllegalArgumentException("Unexpected character '$ch' at position $idx")
            }
        }

        val value = parseValueRef()
        skipWs()
        if (idx != len) {
            throw IllegalArgumentException("Trailing characters after JSON value at position $idx")
        }
        return value
    }

    private fun escapeJsonString(str: String): String {
        val sb = StringBuilder("\"")
        for (ch in str) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun canonicalizeJson(value: Any?): String {
        return when (value) {
            null -> "null"
            is Boolean -> if (value) "true" else "false"
            is Int -> value.toString()
            is Long -> {
                if (value < -9007199254740991L || value > 9007199254740991L) {
                    throw IllegalArgumentException("Integer out of safe range: $value")
                }
                value.toString()
            }
            is Number -> throw IllegalArgumentException("Canonical JSON allows only integer numbers")
            is String -> escapeJsonString(value)
            is List<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") {
                canonicalizeJson(it)
            }
            is Map<*, *> -> {
                val sortedKeys = value.keys
                    .map { it as? String ?: throw IllegalArgumentException("Map keys must be Strings") }
                    .sorted()
                sortedKeys.joinToString(prefix = "{", postfix = "}", separator = ",") { k ->
                    "${escapeJsonString(k)}:${canonicalizeJson(value[k])}"
                }
            }
            else -> throw IllegalArgumentException("Unsupported type in canonical JSON: ${value::class.java.name}")
        }
    }

    fun assertNoProhibitedPayloadContent(commandType: String, payload: Map<String, Any?>) {
        fun containsProhibited(v: Any?): Boolean {
            return when (v) {
                is Map<*, *> -> {
                    for ((k, child) in v) {
                        val keyStr = (k as? String)?.lowercase() ?: ""
                        if (keyStr in setOf("decrypt_keys", "decryptkeys", "clear_pin", "ctmk", "zmk")) {
                            return true
                        }
                        if (k == "params_json" && child is String) {
                            try {
                                val parsed = parseJsonRejectingDuplicateKeys(child)
                                if (containsProhibited(parsed)) return true
                            } catch (_: Exception) {
                                // ignore inner parse failure here
                            }
                        }
                        if (containsProhibited(child)) return true
                    }
                    false
                }
                is List<*> -> v.any { containsProhibited(it) }
                else -> false
            }
        }

        if (containsProhibited(payload)) {
            throw IllegalArgumentException(
                "Command $commandType payload contains prohibited key material (e.g. decrypt_keys)"
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun validateCanonicalCommandClaims(raw: Any?): CanonicalCommandClaims {
        val obj = raw as? Map<String, Any?>
            ?: throw IllegalArgumentException("Command claims must be a JSON object")
        val keys = obj.keys.sorted()
        if (keys != REQUIRED_CLAIM_KEYS) {
            throw IllegalArgumentException(
                "Command claims keys mismatch: expected $REQUIRED_CLAIM_KEYS, got $keys"
            )
        }

        val protocolVersion = obj["protocol_version"] as? Long
            ?: throw IllegalArgumentException("protocol_version must be an integer")
        if (protocolVersion != COMMAND_PROTOCOL_VERSION.toLong()) {
            throw IllegalArgumentException("Unsupported command protocol_version: $protocolVersion")
        }

        val alg = obj["alg"] as? String
            ?: throw IllegalArgumentException("alg must be a string")
        if (alg != COMMAND_SIGNATURE_ALG) {
            throw IllegalArgumentException("Unsupported command signature algorithm: $alg")
        }

        fun requireNonEmptyString(field: String, maxLen: Int = 256): String {
            val v = obj[field] as? String
                ?: throw IllegalArgumentException("Invalid or empty string field \"$field\"")
            if (v.trim() != v || v.isEmpty() || v.length > maxLen) {
                throw IllegalArgumentException("Invalid or empty string field \"$field\"")
            }
            return v
        }

        val commandId = requireNonEmptyString("command_id", 128)
        val deviceId = requireNonEmptyString("device_id", 128)
        val deviceIdentityRef = requireNonEmptyString("device_identity_ref", 256)
        val tenantId = requireNonEmptyString("tenant_id", 128)
        val issuer = requireNonEmptyString("issuer", 128)
        val keyId = requireNonEmptyString("key_id", 64)
        if (!KEY_ID_REGEX.matches(keyId) || !keyId.startsWith("cmd-")) {
            throw IllegalArgumentException("Invalid command signing key_id \"$keyId\"")
        }

        val type = requireNonEmptyString("type", 64)
        if (!COMMAND_TYPE_REGEX.matches(type)) {
            throw IllegalArgumentException("Invalid command type \"$type\"")
        }

        val nonce = requireNonEmptyString("nonce", 128)
        if (nonce.length < 16 || !BASE64URL_REGEX.matches(nonce)) {
            throw IllegalArgumentException("Invalid command nonce")
        }
        val nonceBytes = fromBase64UrlStrict(nonce, "nonce")
        if (nonceBytes.size < 12) {
            throw IllegalArgumentException("Command nonce must contain at least 96 bits of entropy")
        }

        val approvalRef: String? = if (obj["approval_ref"] == null) {
            null
        } else {
            requireNonEmptyString("approval_ref", 128)
        }

        val issuedAtIso = requireNonEmptyString("issued_at", 32)
        val notBeforeIso = requireNonEmptyString("not_before", 32)
        val expiresAtIso = requireNonEmptyString("expires_at", 32)

        val issuedAtSec = parseUtcIsoSeconds(issuedAtIso, "issued_at")
        val notBeforeSec = parseUtcIsoSeconds(notBeforeIso, "not_before")
        val expiresAtSec = parseUtcIsoSeconds(expiresAtIso, "expires_at")

        if (notBeforeSec < issuedAtSec) {
            throw IllegalArgumentException("not_before cannot be earlier than issued_at")
        }
        if (expiresAtSec <= issuedAtSec || expiresAtSec <= notBeforeSec) {
            throw IllegalArgumentException("expires_at must be strictly greater than issued_at and not_before")
        }

        val ttlSec = expiresAtSec - issuedAtSec
        val maxTtl = if (SENSITIVE_SHORT_TTL_COMMANDS.contains(type)) {
            MAX_SENSITIVE_TTL_SECONDS
        } else {
            MAX_STANDARD_TTL_SECONDS
        }
        if (ttlSec > maxTtl) {
            throw IllegalArgumentException("Command TTL (${ttlSec}s) exceeds maximum allowed (${maxTtl}s) for type $type")
        }

        val payloadObj = obj["payload"] as? Map<String, Any?>
            ?: throw IllegalArgumentException("Command payload must be a JSON object")
        assertNoProhibitedPayloadContent(type, payloadObj)

        return CanonicalCommandClaims(
            alg = COMMAND_SIGNATURE_ALG,
            approvalRef = approvalRef,
            commandId = commandId,
            deviceId = deviceId,
            deviceIdentityRef = deviceIdentityRef,
            expiresAt = expiresAtIso,
            issuedAt = issuedAtIso,
            issuer = issuer,
            keyId = keyId,
            nonce = nonce,
            notBefore = notBeforeIso,
            payload = payloadObj,
            protocolVersion = 1L,
            tenantId = tenantId,
            type = type
        )
    }

    fun parseAndValidateCompactCommandJws(commandJws: String): ParsedCompactCommandJws {
        if (commandJws.isBlank()) {
            throw IllegalArgumentException("command_jws must be a non-empty string")
        }
        val parts = commandJws.split(".")
        if (parts.size != 3) {
            throw IllegalArgumentException("Malformed compact JWS: expected exactly 3 segments")
        }
        val headerB64 = parts[0]
        val payloadB64 = parts[1]
        val signatureB64 = parts[2]

        val headerBytes = fromBase64UrlStrict(headerB64, "jws.header")
        val payloadBytes = fromBase64UrlStrict(payloadB64, "jws.payload")
        val signatureBytes = fromBase64UrlStrict(signatureB64, "jws.signature")

        if (signatureBytes.size != 64) {
            throw IllegalArgumentException("Invalid ES256 signature length: expected 64 bytes, got ${signatureBytes.size}")
        }

        val headerText = String(headerBytes, StandardCharsets.UTF_8)
        val payloadText = String(payloadBytes, StandardCharsets.UTF_8)

        @Suppress("UNCHECKED_CAST")
        val hObj = parseJsonRejectingDuplicateKeys(headerText) as? Map<String, Any?>
            ?: throw IllegalArgumentException("JWS protected header must be a JSON object")

        val alg = hObj["alg"] as? String
        if (alg != COMMAND_SIGNATURE_ALG) {
            throw IllegalArgumentException("Unsupported JWS header alg: $alg")
        }
        val kid = hObj["kid"] as? String
        if (kid.isNullOrBlank()) {
            throw IllegalArgumentException("Missing JWS header kid")
        }
        if (!KEY_ID_REGEX.matches(kid)) {
            throw IllegalArgumentException("Invalid JWS header kid")
        }
        val typ = hObj["typ"] as? String
        if (typ != COMMAND_JWS_TYP) {
            throw IllegalArgumentException("Invalid JWS header typ: $typ")
        }
        val v = hObj["v"] as? Long
        if (v != COMMAND_PROTOCOL_VERSION.toLong()) {
            throw IllegalArgumentException("Unsupported JWS header version: $v")
        }
        if (hObj.keys.sorted() != listOf("alg", "kid", "typ", "v")) {
            throw IllegalArgumentException("JWS header contains unexpected or missing fields")
        }

        val rawClaims = parseJsonRejectingDuplicateKeys(payloadText)
        val claims = validateCanonicalCommandClaims(rawClaims)

        if (kid != claims.keyId) {
            throw IllegalArgumentException("JWS header kid does not match payload key_id")
        }

        val canonicalHeaderMap = TreeMap<String, Any?>().apply {
            put("alg", COMMAND_SIGNATURE_ALG)
            put("kid", claims.keyId)
            put("typ", COMMAND_JWS_TYP)
            put("v", 1L)
        }
        val expectedHeaderB64 = toBase64Url(
            canonicalizeJson(canonicalHeaderMap).toByteArray(StandardCharsets.UTF_8)
        )
        val expectedPayloadB64 = toBase64Url(
            canonicalizeJson(claims.toCanonicalMap()).toByteArray(StandardCharsets.UTF_8)
        )

        if (headerB64 != expectedHeaderB64 || payloadB64 != expectedPayloadB64) {
            throw IllegalArgumentException("Non-canonical JSON serialization detected in JWS header or payload")
        }

        val signingInput = "$headerB64.$payloadB64"
        return ParsedCompactCommandJws(
            header = CanonicalProtectedHeader(
                alg = COMMAND_SIGNATURE_ALG,
                kid = claims.keyId,
                typ = COMMAND_JWS_TYP,
                v = 1L
            ),
            claims = claims,
            signatureBytes = signatureBytes,
            signatureB64 = signatureB64,
            signingInputBytes = signingInput.toByteArray(StandardCharsets.US_ASCII)
        )
    }
}
