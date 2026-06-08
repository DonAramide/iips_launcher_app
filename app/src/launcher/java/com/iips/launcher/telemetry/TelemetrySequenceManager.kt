package com.iips.launcher.telemetry

import android.content.Context
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelemetrySequenceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Retrieves the next monotonic sequence number and increments the persistent counter.
     */
    @Synchronized
    fun getNextSequence(): Long {
        return SecurePreferences.getNextTelemetrySeq(context)
    }

    /**
     * Retrieves the current sequence number without incrementing it.
     */
    fun getCurrentSequence(): Long {
        return SecurePreferences.getCurrentTelemetrySeq(context)
    }
}
