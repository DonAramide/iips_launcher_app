package com.iips.launcher.convergence

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.iips.launcher.network.models.HeartbeatRequest
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult

/**
 * PHASE 1 — RUNTIME CONNECTION MANAGER
 * 
 * Production-grade persistence engine managing the real-time enterprise WebSocket lifecycle.
 * Enforces strict bounded exponential retry pacing with random jitter to prevent server-side storms.
 * Computes live runtime connection health scores and synchronizes edge frames securely.
 */
@Singleton
class DotroidRuntimeConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "DotroidConnectionMgr"
        private const val GATEWAY_WS_URL = "wss://api-quasar.iips.app/api/v1/do-mdm/devices/ws"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val BASE_RECONNECT_DELAY_MS = 2_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private val connectionMutex = Mutex()
    private val gson = Gson()

    private val _isConnected = AtomicBoolean(false)
    val isConnected: Boolean
        get() = _isConnected.get()

    // Real-time Health Score: 0.0 (dead) to 1.0 (pristine)
    private var _healthScore = 1.0
    val healthScore: Double
        get() = _healthScore

    private val reconnectAttempts = AtomicInteger(0)
    private val missedPongs = AtomicInteger(0)
    private var heartbeatJob: Job? = null

    // Upstream event decoupling layer for incoming edge packets
    private val _incomingFrames = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incomingFrames: SharedFlow<String> = _incomingFrames.asSharedFlow()

    /**
     * Initializes the convergence connection layer safely.
     */
    fun startConnection() {
        scope.launch {
            establishWebSocket()
        }
    }

    private suspend fun establishWebSocket() = connectionMutex.withLock {
        if (_isConnected.get() && webSocket != null) {
            Log.d(TAG, "Connection is already live and bound.")
            return@withLock
        }

        val token = SecurePreferences.getDeviceToken(context)
        if (token.isNullOrBlank()) {
            Log.w(TAG, "Device configuration absent or unmapped. Postponing WebSocket convergence sequence.")
            scheduleDegradedReconnect()
            return@withLock
        }

        Log.i(TAG, "Initiating real-time edge runtime connection to gateway...")
        val wsUrl = SecurePreferences.getWebSocketUrl(context)
        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Enterprise Gateway WebSocket Connection Verified [Status Code: ${response.code}]")
                _isConnected.set(true)
                reconnectAttempts.set(0)
                missedPongs.set(0)
                _healthScore = 1.0
                startHeartbeatTransmission()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.v(TAG, "Ingested frame payload: ${text.take(200)}")
                if (text.contains("HEARTBEAT_PONG")) {
                    missedPongs.set(0)
                    Log.d(TAG, "Authorized backend pong payload intercepted. SLA constraint verified.")
                }
                _healthScore = min(1.0, _healthScore + 0.05) // Increment health score positively
                scope.launch {
                    _incomingFrames.emit(text)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket stream cleanly finalized by remote peer [Code: $code, Reason: $reason]")
                handleSocketFault(isRemoteTermination = true)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket transport degradation encountered: ${t.message}")
                handleSocketFault(isRemoteTermination = false)
            }
        })
    }

    /**
     * Replay-safe event frame transmission guard.
     * Yields false if packet transport must be deferred to persistent queue layers.
     */
    fun transmitFrame(payload: String): Boolean {
        if (!_isConnected.get() || webSocket == null) {
            Log.w(TAG, "Transport layer absent. Routing frame payload to offline replay buffers.")
            _healthScore = (_healthScore - 0.1).coerceAtLeast(0.0)
            return false
        }

        return try {
            val sent = webSocket?.send(payload) == true
            if (!sent) {
                _healthScore = (_healthScore - 0.05).coerceAtLeast(0.0)
            }
            sent
        } catch (e: Exception) {
            Log.e(TAG, "Exception transmitting socket frame", e)
            _healthScore = (_healthScore - 0.1).coerceAtLeast(0.0)
            false
        }
    }

    private fun startHeartbeatTransmission() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && _isConnected.get()) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendRuntimeHeartbeat()
            }
        }
    }

    private fun sendRuntimeHeartbeat() {
        val deviceId = SecurePreferences.getDeviceId(context) ?: "UNKNOWN_DEVICE"
        val tenantId = SecurePreferences.getTenantId(context) ?: "default"
        
        // Check SLA Breach Policy: Two missed server Pongs consecutively
        if (missedPongs.incrementAndGet() >= 2) {
            Log.e(TAG, "SLA Breach Policy triggered: Observed two missed server Pong responses consecutively. Resetting socket layers.")
            handleSocketFault(isRemoteTermination = false)
            return
        }

        val batteryInfo = com.iips.launcher.core.HardwareProvider.getBatteryInfo(context)
        val networkInfo = com.iips.launcher.core.HardwareProvider.getNetworkInfo(context)
        val uptime = com.iips.launcher.core.HardwareProvider.getUptimeSeconds()
        val simInfo = com.iips.launcher.core.HardwareProvider.getSimInfo(context)

        val pingMap = mapOf(
            "type" to "HEARTBEAT_PING",
            "edgeNodeId" to deviceId,
            "tenantId" to tenantId,
            "clientEpoch" to (System.currentTimeMillis() / 1000L),
            "batteryPercentage" to batteryInfo.level,
            "networkType" to networkInfo.type,
            "uptime" to uptime,
            "isSimPresent" to simInfo.isPresent,
            "simOperator" to simInfo.simOperator,
            "simNetworkType" to simInfo.simNetworkType
        )

        val success = transmitFrame(gson.toJson(pingMap))
        if (success) {
            Log.d(TAG, "Runtime edge heartbeat ping safely transmitted: $pingMap")
        }

        // Send current GPS coordinates during heartbeat ping
        sendLocationReportDuringHeartbeat()
    }

    private fun sendLocationReportDuringHeartbeat() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    transmitLocationReport(location.latitude, location.longitude, location.accuracy)
                } else {
                    val locationRequest = LocationRequest.Builder(
                        Priority.PRIORITY_HIGH_ACCURACY, 1000L
                    ).setMaxUpdates(1).build()

                    val callback = object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            val loc = result.lastLocation
                            if (loc != null) {
                                transmitLocationReport(loc.latitude, loc.longitude, loc.accuracy)
                            }
                        }
                    }
                    fusedLocationClient.requestLocationUpdates(
                        locationRequest, callback, android.os.Looper.getMainLooper()
                    )
                }
            }.addOnFailureListener { e ->
                Log.e(TAG, "Failed to get location for heartbeat report", e)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission missing for heartbeat location request", e)
        }
    }

    private fun transmitLocationReport(lat: Double, lng: Double, accuracy: Float) {
        val reqId = "loc-hb-${System.currentTimeMillis()}"
        val dataMap = mapOf(
            "latitude" to lat,
            "longitude" to lng,
            "accuracy" to accuracy.toDouble()
        )
        val frameMap = mapOf(
            "type" to "LOCATION_REPORT",
            "request_id" to reqId,
            "data" to dataMap
        )
        val jsonFrame = gson.toJson(frameMap)
        transmitFrame(jsonFrame)
    }

    private fun handleSocketFault(isRemoteTermination: Boolean) {
        _isConnected.set(false)
        heartbeatJob?.cancel()
        _healthScore = (_healthScore - if (isRemoteTermination) 0.2 else 0.3).coerceAtLeast(0.0)
        
        try {
            webSocket?.close(1000, "Local Fault Handled")
        } catch (e: Exception) {}
        webSocket = null

        scheduleDegradedReconnect()
    }

    /**
     * Enforces strictly bounded exponential pacing to avoid triggering upstream gateway reconnect storms.
     */
    private fun scheduleDegradedReconnect() {
        val attempt = reconnectAttempts.incrementAndGet()
        // Exponential calculation: base * 2^(attempt - 1)
        val exponentialDelay = BASE_RECONNECT_DELAY_MS * (2.0.pow(attempt - 1)).toLong()
        // Full jitter calculation: uniform random between 0 and exponentialDelay
        val boundedDelay = min(MAX_RECONNECT_DELAY_MS, exponentialDelay)
        val jitteredDelay = Random.nextLong(BASE_RECONNECT_DELAY_MS, boundedDelay + 1)

        Log.w(TAG, "Connection offline. Reconnect attempt #$attempt bounded to execution in ${jitteredDelay}ms.")

        scope.launch {
            delay(jitteredDelay)
            establishWebSocket()
        }
    }

    /**
     * Shuts down connection pools gracefully on system tear down.
     */
    fun terminateRuntime() {
        Log.i(TAG, "Tearing down Dotroid Connection Convergence layer.")
        _isConnected.set(false)
        heartbeatJob?.cancel()
        scope.cancel()
        try {
            webSocket?.close(1000, "Runtime Terminated")
        } catch (e: Exception) {}
        webSocket = null
    }
}
