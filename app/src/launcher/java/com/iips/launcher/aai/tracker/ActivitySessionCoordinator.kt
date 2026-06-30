package com.iips.launcher.aai.tracker

import android.content.Context
import android.util.Log
import com.iips.launcher.aai.monitor.ForegroundMonitor

class ActivitySessionCoordinator(private val context: Context) {

    private val stateMachine = ActivityStateMachine { newState ->
        handleStateChange(newState)
    }

    private val foregroundMonitor = ForegroundMonitor(context) { packageName, eventType ->
        handleForegroundEvent(packageName, eventType)
    }

    fun startCoordinating() {
        Log.d("ActivitySessionCoordinator", "Starting AAI coordination")
        foregroundMonitor.startMonitoring()
    }

    fun stopCoordinating() {
        Log.d("ActivitySessionCoordinator", "Stopping AAI coordination")
        foregroundMonitor.stopMonitoring()
    }

    private fun handleStateChange(newState: ActivityState) {
        // Build event using ApplicationActivityEventBuilder and send to WAL
        Log.d("ActivitySessionCoordinator", "New State: \$newState")
    }

    private fun handleForegroundEvent(packageName: String, eventType: String) {
        // e.g., if eventType == "APPLICATION_FOREGROUND", transition state machine
        if (eventType == "APPLICATION_FOREGROUND") {
            stateMachine.transitionTo(ActivityState.FOREGROUND_ACTIVE)
        } else if (eventType == "APPLICATION_BACKGROUND") {
            stateMachine.transitionTo(ActivityState.BACKGROUND)
        }
    }
}
