package com.iips.launcher.aai.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lightweight thread-safe in-memory TTL cache for AAI repository data.
 *
 * - Thread safety via Kotlin [Mutex] (coroutine-friendly, non-blocking for readers)
 * - Entries expire after [ttlMs] milliseconds (default 30 s)
 * - Individual keys can be invalidated explicitly (e.g. on export completion or WS event)
 * - Full cache can be wiped (e.g. on logout / filter change)
 *
 * This class does NOT use a background eviction thread; stale entries are evicted
 * lazily on the next [get] call for that key. This is intentional: the cache is
 * small (≤ 6 live keys at any time) and a background thread would be wasteful.
 */
class AaiMemoryCache(private val ttlMs: Long = DEFAULT_TTL_MS) {

    companion object {
        const val DEFAULT_TTL_MS = 30_000L   // 30 seconds

        // Well-known cache keys
        const val KEY_DASHBOARD    = "dashboard"
        const val KEY_LIVE_FEED    = "live_feed"
        const val KEY_APPS         = "apps"
        const val KEY_SESSIONS     = "sessions"
        const val KEY_COMPLIANCE   = "compliance"
        const val KEY_VIOLATIONS   = "violations"
        const val KEY_NOTIFICATIONS = "notifications"
    }

    private data class Entry<T>(val value: T, val storedAt: Long = System.currentTimeMillis()) {
        fun isExpired(ttlMs: Long) = System.currentTimeMillis() - storedAt > ttlMs
    }

    private val mutex = Mutex()
    private val store = mutableMapOf<String, Entry<*>>()

    /**
     * Returns the cached value for [key] if present and not expired, otherwise null.
     * Stale entries are removed lazily.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> get(key: String): T? = mutex.withLock {
        val entry = store[key] as? Entry<T> ?: return@withLock null
        if (entry.isExpired(ttlMs)) {
            store.remove(key)
            null
        } else {
            entry.value
        }
    }

    /**
     * Stores [value] under [key], replacing any existing entry.
     */
    suspend fun <T> put(key: String, value: T) = mutex.withLock {
        store[key] = Entry(value)
    }

    /**
     * Invalidates one or more keys. Safe to call with keys that don't exist.
     */
    suspend fun invalidate(vararg keys: String) = mutex.withLock {
        keys.forEach { store.remove(it) }
    }

    /**
     * Invalidates all cached data.
     */
    suspend fun invalidateAll() = mutex.withLock {
        store.clear()
    }

    /**
     * Returns true if a non-expired entry exists for [key].
     */
    suspend fun isFresh(key: String): Boolean = mutex.withLock {
        val entry = store[key] ?: return@withLock false
        !entry.isExpired(ttlMs)
    }
}
