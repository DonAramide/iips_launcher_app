package com.iips.launcher.aai.monitor

import android.os.SystemClock

object InteractionMonitor {
    private var lastInteractionTimeMs: Long = 0
    private const val IDLE_THRESHOLD_MS = 5000L // 5 seconds of no interaction = IDLE

    fun recordInteraction() {
        lastInteractionTimeMs = SystemClock.elapsedRealtime()
    }

    fun isUserActive(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return (now - lastInteractionTimeMs) < IDLE_THRESHOLD_MS
    }

    fun getIdleDurationMs(): Long {
        if (isUserActive()) return 0
        return SystemClock.elapsedRealtime() - (lastInteractionTimeMs + IDLE_THRESHOLD_MS)
    }
}
