package com.iips.launcher.convergence

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.iips.launcher.network.ConfigService
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional Path A HTTP command poll when WebSocket is unavailable.
 *
 * Guardrails:
 * - Device JWT only (never Tenant JWT).
 * - Bounded: at most one in-flight poll; minimum interval 15s.
 * - Disabled unless SecurePreferences.getPathAHttpCommandPollEnabled is true
 *   OR the runtime connection manager is already in HTTP fallback mode.
 * - Delivery still requires [RemoteCommandExecutionEngine] + PdsCommandGate.
 */
@Singleton
class PathACommandHttpFallback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configService: ConfigService,
    private val commandEngine: RemoteCommandExecutionEngine
) {
    companion object {
        private const val TAG = "PathACmdHttpFallback"
        private const val MIN_INTERVAL_MS = 15_000L
    }

    private val mutex = Mutex()
    private val gson = Gson()
    private var lastPollAt = 0L

    suspend fun pollIfDue(forceBecauseWsDown: Boolean = false): Int = mutex.withLock {
        val enabled =
            SecurePreferences.getPathAHttpCommandPollEnabled(context) || forceBecauseWsDown
        if (!enabled) return 0
        val now = System.currentTimeMillis()
        if (now - lastPollAt < MIN_INTERVAL_MS) return 0
        lastPollAt = now

        val token = SecurePreferences.getDeviceToken(context) ?: return 0
        return try {
            val resp = configService.pollPathACommands("Bearer $token")
            if (!resp.isSuccessful) {
                Log.w(TAG, "Path A poll HTTP ${resp.code()}")
                return 0
            }
            val body = resp.body().orEmpty()
            var delivered = 0
            for (cmd in body) {
                val frame = gson.toJson(
                    mapOf(
                        "type" to "command",
                        "command_id" to (cmd["id"] ?: cmd["command_id"]),
                        "signature" to cmd["signature"],
                        "command_jws" to cmd["command_jws"],
                        "command" to cmd
                    )
                )
                commandEngine.processCommandFrame(frame)
                delivered += 1
            }
            delivered
        } catch (e: Exception) {
            Log.e(TAG, "Path A poll failed: ${e.message}")
            0
        }
    }
}
