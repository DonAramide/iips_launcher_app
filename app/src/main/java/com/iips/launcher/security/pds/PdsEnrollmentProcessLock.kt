package com.iips.launcher.security.pds

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide single-flight for PDS enrollment. Concurrent enrollIfNeeded
 * callers share this lock so only one challenge/keygen proceeds.
 */
object PdsEnrollmentProcessLock {
    private val mutex = Mutex()

    suspend fun <T> runExclusive(block: suspend () -> T): T {
        return mutex.withLock { block() }
    }
}
