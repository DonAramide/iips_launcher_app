package com.iips.launcher.watchdog

import android.content.Context
import android.util.Log
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages crash detection and state recovery for the launcher.
 */
@Singleton
class CrashRecoveryManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CrashRecovery"
        private const val MAX_CRASH_THRESHOLD = 3
        private const val RESET_WINDOW_MS = 10 * 60 * 1000L // 10 minutes
    }

    /**
     * Records a crash and returns true if the device should enter a recovery state.
     */
    fun recordCrash(): Boolean {
        val lastCrashTime = SecurePreferences.getLastCrashTime(context)
        val currentTime = System.currentTimeMillis()
        var crashCount = SecurePreferences.getCrashCount(context)

        if (currentTime - lastCrashTime > RESET_WINDOW_MS) {
            crashCount = 1
        } else {
            crashCount++
        }

        SecurePreferences.setCrashCount(context, crashCount)
        SecurePreferences.setLastCrashTime(context, currentTime)

        Log.w(TAG, "Launcher crashed. Count: \$crashCount")

        return crashCount >= MAX_CRASH_THRESHOLD
    }

    /**
     * Resets the crash counter.
     */
    fun clearCrashes() {
        SecurePreferences.setCrashCount(context, 0)
    }
}
