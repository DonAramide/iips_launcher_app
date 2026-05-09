package com.iips.launcher.core

import android.util.Log
import com.google.gson.Gson
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides structured JSON logging for enterprise observability and telemetry correlation.
 */
@Singleton
class StructuredLogger @Inject constructor() {
    private val gson = Gson()

    /**
     * Logs a structured event with metadata and correlation IDs.
     */
    fun logEvent(tag: String, type: String, message: String, metadata: Map<String, Any> = emptyMap()) {
        val event = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "correlation_id" to UUID.randomUUID().toString(),
            "type" to type,
            "message" to message,
            "metadata" to metadata
        )
        
        val json = gson.toJson(event)
        Log.i(tag, "[STRUCTURED_EVENT] \$json")
    }

    /**
     * Logs a critical system incident.
     */
    fun logIncident(tag: String, errorCode: String, description: String, fatal: Boolean = false) {
        val incident = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "incident_id" to UUID.randomUUID().toString(),
            "error_code" to errorCode,
            "description" to description,
            "fatal" to fatal
        )
        
        val json = gson.toJson(incident)
        if (fatal) {
            Log.e(tag, "[CRITICAL_INCIDENT] \$json")
        } else {
            Log.w(tag, "[SYSTEM_INCIDENT] \$json")
        }
    }
}
