package com.iips.launcher.convergence

/**
 * Stage 2A.3.6 — classify Path A HTTP poll/ack failures for Dotroid device JWT.
 *
 * Distinct from Socket.IO structured ack errors and from DoMdm WS `type=error`.
 * Quasar envelope may wrap business payloads; this classifier uses HTTP status
 * (+ optional sanitized message) only.
 */
object PathAHttpErrorClassifier {
    data class Classified(
        val code: String,
        val retryable: Boolean
    )

    fun classify(httpStatus: Int, message: String? = null): Classified {
        val msg = message?.lowercase().orEmpty()
        return when {
            httpStatus == 401 || msg.contains("unauthorized") ->
                Classified("unauthorized", retryable = false)
            // Skew is not a transient transport failure: the signed ack_ts is fixed.
            // Delivery retains the exact proof as EXPIRED_RETAINED (no re-sign).
            msg.contains("skew") ->
                Classified("ack_ts_out_of_skew", retryable = false)
            httpStatus == 403 || msg.contains("forbidden") || msg.contains("proof") ->
                Classified("forbidden_or_proof", retryable = false)
            httpStatus == 404 || msg.contains("not found") ->
                Classified("command_not_found", retryable = false)
            httpStatus == 409 || msg.contains("nonce") || msg.contains("replay") ->
                Classified("ack_nonce_replay", retryable = false)
            httpStatus in 500..599 ->
                Classified("http_server_error", retryable = true)
            httpStatus == 408 || httpStatus == 429 ->
                Classified("http_timeout_or_throttle", retryable = true)
            httpStatus == 0 ->
                Classified("network_failure", retryable = true)
            else ->
                Classified("http_rejected", retryable = false)
        }
    }
}
