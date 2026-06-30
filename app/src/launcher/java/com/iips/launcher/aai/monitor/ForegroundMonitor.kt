package com.iips.launcher.aai.monitor

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper

class ForegroundMonitor(
    private val context: Context,
    private val onForegroundEvent: (String, String) -> Unit
) {
    private var isMonitoring = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastTimestamp = System.currentTimeMillis()
    private var currentForegroundApp: String? = null

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                checkUsageStats()
                handler.postDelayed(this, 1000) // Poll every second
            }
        }
    }

    fun startMonitoring() {
        if (!isMonitoring) {
            isMonitoring = true
            lastTimestamp = System.currentTimeMillis()
            handler.post(monitorRunnable)
        }
    }

    fun stopMonitoring() {
        isMonitoring = false
        handler.removeCallbacks(monitorRunnable)
    }

    private fun checkUsageStats() {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastTimestamp, now)

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                if (currentForegroundApp != event.packageName) {
                    currentForegroundApp = event.packageName
                    onForegroundEvent(event.packageName, "APPLICATION_FOREGROUND")
                }
            } else if (event.eventType == UsageEvents.Event.ACTIVITY_PAUSED || event.eventType == UsageEvents.Event.ACTIVITY_STOPPED) {
                if (currentForegroundApp == event.packageName) {
                    currentForegroundApp = null
                    onForegroundEvent(event.packageName, "APPLICATION_BACKGROUND")
                }
            }
        }
        lastTimestamp = now
    }
}
