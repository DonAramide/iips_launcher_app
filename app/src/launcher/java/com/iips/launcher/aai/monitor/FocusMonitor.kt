package com.iips.launcher.aai.monitor

import android.os.SystemClock

class FocusMonitor {
    private var sessionStartTime: Long = 0
    private var lastStateChangeTime: Long = 0
    private var totalForegroundTime: Long = 0
    private var totalInteractionTime: Long = 0
    private var totalIdleTime: Long = 0

    fun startSession() {
        val now = SystemClock.elapsedRealtime()
        sessionStartTime = now
        lastStateChangeTime = now
        totalForegroundTime = 0
        totalInteractionTime = 0
        totalIdleTime = 0
    }

    fun updateState(isActive: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val duration = now - lastStateChangeTime
        
        if (isActive) {
            totalInteractionTime += duration
        } else {
            totalIdleTime += duration
        }
        totalForegroundTime += duration
        lastStateChangeTime = now
    }

    fun getForegroundDuration(): Long = totalForegroundTime
    fun getInteractionDuration(): Long = totalInteractionTime
    fun getIdleDuration(): Long = totalIdleTime
}
