package com.iips.launcher.security.pds

/**
 * Parses Android Enterprise / Dotroid QR payloads without camera APIs.
 * Tenant ID is a required PDS enrollment input and must be extracted here
 * so onboarding can persist it before [com.iips.launcher.network.DeviceEnrollmentManager]
 * runs.
 */
object QrProvisioningPayloadParser {
    data class Payload(
        val enrollmentToken: String,
        val backendUrl: String?,
        val tenantId: String?
    )

    fun parse(rawValue: String): Payload {
        val trimmed = rawValue.trim()
        if (!trimmed.startsWith("{")) {
            return Payload(enrollmentToken = trimmed, backendUrl = null, tenantId = null)
        }
        val extras = extractObject(trimmed, "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE")
        val token = extractString(trimmed, "enrollment_token")
            ?: extras?.let { extractString(it, "enrollment_token") }
            ?: trimmed
        val backend = extractString(trimmed, "backend_url")
            ?: extras?.let { extractString(it, "backend_url") }
        val tenant = extractString(trimmed, "tenant_id")
            ?: extras?.let { extractString(it, "tenant_id") }
        return Payload(
            enrollmentToken = token,
            backendUrl = backend?.takeIf { it.isNotBlank() },
            tenantId = tenant?.takeIf { it.isNotBlank() }
        )
    }

    private fun extractString(json: String, key: String): String? {
        val regex = Regex(""""${Regex.escape(key)}"\s*:\s*"([^"]*)"""")
        return regex.find(json)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun extractObject(json: String, key: String): String? {
        val startRegex = Regex(""""${Regex.escape(key)}"\s*:\s*\{""")
        val match = startRegex.find(json) ?: return null
        val start = match.range.last
        var depth = 1
        val sb = StringBuilder("{")
        var i = start + 1
        while (i < json.length && depth > 0) {
            val c = json[i]
            sb.append(c)
            if (c == '{') depth++
            if (c == '}') depth--
            i++
        }
        return if (depth == 0) sb.toString() else null
    }
}
