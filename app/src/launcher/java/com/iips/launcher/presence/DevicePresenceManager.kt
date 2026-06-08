package com.iips.launcher.presence

import android.util.Log
import com.iips.launcher.core.StructuredLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the high-level presence and operational state of the device runtime.
 */
@Singleton
class DevicePresenceManager @Inject constructor(
    private val structuredLogger: StructuredLogger
) {
    companion object {
        private const val TAG = "DevicePresence"
    }

    enum class PresenceState {
        ONLINE,      // Healthy and connected
        DEGRADED,    // Functional but with minor drift or failed workers
        RECOVERING,  // Actively performing self-healing
        STALE,       // Outdated policy or telemetry backlog
        QUARANTINED, // Critical security failure or unmanaged state
        OFFLINE      // No network or system frozen
    }

    private val _currentState = MutableStateFlow(PresenceState.ONLINE)
    val currentState: StateFlow<PresenceState> = _currentState

    /**
     * Updates the presence state and logs the transition.
     */
    fun updateState(newState: PresenceState, reason: String? = null) {
        if (_currentState.value != newState) {
            val oldState = _currentState.value
            _currentState.value = newState
            
            Log.w(TAG, "Presence transition: \$oldState -> \$newState (Reason: \$reason)")
            
            structuredLogger.logEvent(
                TAG,
                "PRESENCE_TRANSITION",
                "Device presence changed",
                mapOf(
                    "old_state" to oldState.name,
                    "new_state" to newState.name,
                    "reason" to (reason ?: "unspecified")
                )
            )
        }
    }

    /**
     * Helper to move into recovering state.
     */
    fun startRecovery(reason: String) = updateState(PresenceState.RECOVERING, reason)

    /**
     * Helper to move back to online/degraded after recovery.
     */
    fun endRecovery(success: Boolean) {
        if (success) {
            updateState(PresenceState.ONLINE, "Recovery completed successfully")
        } else {
            updateState(PresenceState.DEGRADED, "Recovery failed or incomplete")
        }
    }
}
