package com.iips.launcher.convergence

import android.content.Context
import android.util.Log
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ensures that MDM commands are executed safely and converge to the desired state.
 * Prevents loops, storms, and duplicate execution.
 */
@Singleton
class CommandConvergenceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CommandConvergence"
        private const val MAX_COMMAND_RETRY = 3
        private const val RETRY_WINDOW_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    /**
     * Checks if a command is safe to execute.
     */
    fun isSafeToExecute(commandId: String, commandType: String): Boolean {
        // 1. Prevent duplicate execution of the same ID
        if (SecurePreferences.isCommandExecuted(context, commandId)) {
            Log.w(TAG, "Command \$commandId already executed. Skipping.")
            return false
        }

        // 2. Prevent reboot storms
        if (commandType == "REBOOT") {
            val lastReboot = SecurePreferences.getLastRebootTime(context)
            if (System.currentTimeMillis() - lastReboot < 30 * 60 * 1000) { // 30 mins
                Log.e(TAG, "Preventing reboot storm. Last reboot was too recent.")
                return false
            }
        }

        // 3. Track command attempts to prevent infinite failure loops
        val attempts = SecurePreferences.getCommandAttemptCount(context, commandId)
        if (attempts >= MAX_COMMAND_RETRY) {
            Log.e(TAG, "Command \$commandId has exceeded max attempts (\$attempts). Abandoning.")
            return false
        }

        return true
    }

    /**
     * Records a command attempt.
     */
    fun recordAttempt(commandId: String) {
        val current = SecurePreferences.getCommandAttemptCount(context, commandId)
        SecurePreferences.setCommandAttemptCount(context, commandId, current + 1)
    }

    /**
     * Marks a command as successfully converged.
     */
    fun markConverged(commandId: String) {
        SecurePreferences.markCommandAsExecuted(context, commandId)
        // Optionally clear attempt count to save space
    }
}
