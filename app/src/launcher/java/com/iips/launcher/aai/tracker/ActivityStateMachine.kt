package com.iips.launcher.aai.tracker

import android.util.Log

enum class ActivityState {
    LAUNCHING,
    FOREGROUND_ACTIVE,
    FOREGROUND_IDLE,
    BACKGROUND,
    SUSPENDED,
    TERMINATED
}

class ActivityStateMachine(private val onStateChange: (ActivityState) -> Unit) {
    var currentState: ActivityState = ActivityState.BACKGROUND
        private set

    fun transitionTo(newState: ActivityState) {
        // Implement transition rules
        if (isValidTransition(currentState, newState)) {
            Log.d("ActivityStateMachine", "Transitioning from $currentState to $newState")
            currentState = newState
            onStateChange(newState)
        } else {
            Log.w("ActivityStateMachine", "Invalid transition from $currentState to $newState")
        }
    }

    private fun isValidTransition(current: ActivityState, next: ActivityState): Boolean {
        // Define simple valid transitions based on Quasar rules
        return when (current) {
            ActivityState.LAUNCHING -> next == ActivityState.FOREGROUND_ACTIVE || next == ActivityState.TERMINATED
            ActivityState.FOREGROUND_ACTIVE -> next == ActivityState.FOREGROUND_IDLE || next == ActivityState.BACKGROUND || next == ActivityState.TERMINATED
            ActivityState.FOREGROUND_IDLE -> next == ActivityState.FOREGROUND_ACTIVE || next == ActivityState.BACKGROUND || next == ActivityState.TERMINATED
            ActivityState.BACKGROUND -> next == ActivityState.FOREGROUND_ACTIVE || next == ActivityState.SUSPENDED || next == ActivityState.TERMINATED
            ActivityState.SUSPENDED -> next == ActivityState.BACKGROUND || next == ActivityState.TERMINATED
            ActivityState.TERMINATED -> next == ActivityState.LAUNCHING // can restart
        }
    }
}
