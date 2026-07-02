package com.iips.launcher.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.iips.launcher.network.models.AaiStreamFrame
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * AAI WebSocket / SSE stream manager for the Guard console.
 *
 * Consumes the existing Phase 6 backend streaming namespace.
 * Auto-reconnects with exponential back-off + full jitter.
 * Emits parsed [AaiStreamFrame] events on [frames] shared flow.
 *
 * Connection is authenticated using the active manager JWT Bearer token.
 * DO NOT invent new endpoints. Uses the existing backend ws path.
 */
@Singleton
class GuardAaiWebSocketManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "GuardAaiWs"
        private const val AAI_WS_PATH = "ws/manager/aai/stream"
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val BASE_RECONNECT_DELAY_MS = 2_000L
        private const val MAX_RETRY_ATTEMPTS = 10
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()

    private var webSocket: WebSocket? = null
    private var reconnectAttempts = 0
    private var shouldReconnect = false

    private val _frames = MutableSharedFlow<AaiStreamFrame>(extraBufferCapacity = 128)
    val frames: SharedFlow<AaiStreamFrame> = _frames.asSharedFlow()

    private val _connectionState = MutableSharedFlow<ConnectionState>(
        replay = 1,
        extraBufferCapacity = 8
    )
    val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()

    enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED, ERROR }

    fun connect() {
        if (shouldReconnect) return
        shouldReconnect = true
        reconnectAttempts = 0
        openSocket()
    }

    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "Guard closed stream")
        webSocket = null
        scope.launch { _connectionState.emit(ConnectionState.DISCONNECTED) }
    }

    private fun openSocket() {
        val token = SecurePreferences.getGuardAuthToken(context)
        if (token.isNullOrBlank()) {
            Log.w(TAG, "No Guard auth token — deferring WebSocket connection.")
            scheduleReconnect()
            return
        }

        val baseUrl = SecurePreferences.getProvisioningBackendUrl(context)
            ?: SecurePreferences.getBackendUrl(context)
            ?: "http://192.168.1.134:4000/"

        val wsUrl = baseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/api/v1/$AAI_WS_PATH"

        Log.i(TAG, "Connecting to AAI stream: $wsUrl")
        scope.launch { _connectionState.emit(ConnectionState.CONNECTING) }

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "AAI stream connected [${response.code}]")
                reconnectAttempts = 0
                scope.launch { _connectionState.emit(ConnectionState.CONNECTED) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.v(TAG, "AAI frame: ${text.take(200)}")
                parseAndEmit(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "AAI stream closed [$code]: $reason")
                scope.launch { _connectionState.emit(ConnectionState.DISCONNECTED) }
                if (shouldReconnect) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "AAI stream error: ${t.message}")
                scope.launch { _connectionState.emit(ConnectionState.ERROR) }
                if (shouldReconnect) scheduleReconnect()
            }
        })
    }

    private fun parseAndEmit(raw: String) {
        try {
            val frame = gson.fromJson(raw, AaiStreamFrame::class.java)
            scope.launch { _frames.emit(frame) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse AAI frame: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RETRY_ATTEMPTS) {
            Log.e(TAG, "Max AAI stream reconnect attempts reached.")
            shouldReconnect = false
            return
        }
        val attempt = ++reconnectAttempts
        val capped = min(attempt, 10)
        val exp = (BASE_RECONNECT_DELAY_MS * (2.0.pow(capped - 1))).toLong()
        val bounded = min(MAX_RECONNECT_DELAY_MS, exp)
        val jittered = if (bounded > BASE_RECONNECT_DELAY_MS)
            Random.nextLong(BASE_RECONNECT_DELAY_MS, bounded + 1)
        else BASE_RECONNECT_DELAY_MS

        Log.w(TAG, "AAI stream reconnect #$attempt in ${jittered}ms")
        scope.launch {
            delay(jittered)
            if (shouldReconnect) openSocket()
        }
    }

    fun isConnected(): Boolean = connectionState.replayCache.lastOrNull() == ConnectionState.CONNECTED
}
