package com.iips.launcher.mdm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.iips.launcher.config.MdmCommand
import com.iips.launcher.utils.SecurePreferences
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit

import android.os.Handler
import android.os.Looper
import com.iips.launcher.config.CommandAcknowledgement
import com.iips.launcher.config.ConfigService
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Foreground Service that maintains a persistent WebSocket connection for MDM control
 * with production-grade resilience and fallback polling.
 */
class MdmSocketService : Service() {
    private val TAG = "MdmSocketService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private val gson = Gson()
    
    private var retryAttempt = 0
    private val MAX_RETRY_DELAY = 60000L // 1 minute
    
    private val WS_URL = "wss://api-quasar.iips.app/api/v1/do-mdm/devices/ws"
    private val BASE_URL = com.iips.launcher.BuildConfig.BASE_URL
    
    private var isWsConnected = false
    private val pollingHandler = Handler(Looper.getMainLooper())
    private val pollingRunnable = object : Runnable {
        override fun run() {
            if (!isWsConnected) {
                Log.d(TAG, "WebSocket disconnected, waiting for reconnection...")
            }
            pollingHandler.postDelayed(this, 60000) // Poll every 60s
        }
    }

    private lateinit var configService: ConfigService

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
        
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        configService = retrofit.create(ConfigService::class.java)

        // Initialize Sequential Command Processor
        CommandManager.startProcessor(this) { ack ->
            acknowledge(ack)
        }
        
        connect()
        pollingHandler.postDelayed(pollingRunnable, 60000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CommandManager.startProcessor already handles loading pending commands on start
        return START_STICKY
    }

    private fun connect() {
        val token = SecurePreferences.getDeviceToken(this) ?: return
        
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url(WS_URL)
            .addHeader("Authorization", "Bearer $token")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "MDM WebSocket Connected")
                isWsConnected = true
                retryAttempt = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received WS message: $text")
                try {
                    val command = gson.fromJson(text, MdmCommand::class.java)
                    handleCommand(command)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse command", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "MDM WebSocket Failure: ${t.message}")
                isWsConnected = false
                reconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "MDM WebSocket Closing: $reason")
                isWsConnected = false
                reconnect()
            }
        })
    }

    private fun handleCommand(command: MdmCommand) {
        CommandManager.enqueueCommand(this, command)
    }

    private fun acknowledge(ack: CommandAcknowledgement) {
        scope.launch {
            // Track last command for telemetry
            if (ack.status == "SUCCESS" || ack.status == "FAILED") {
                SecurePreferences.setLastCommandId(this@MdmSocketService, ack.commandId)
                SecurePreferences.setLastCommandStatus(this@MdmSocketService, ack.status)
            }

            val json = gson.toJson(ack)
            val sentViaWs = isWsConnected && webSocket?.send(json) == true
            
            if (!sentViaWs) {
                // Fallback to HTTP
                try {
                    val token = SecurePreferences.getDeviceToken(this@MdmSocketService)
                    if (token != null) {
                        val payload = mapOf(
                            "command_id" to ack.commandId,
                            "status" to ack.status,
                            "message" to (ack.message ?: ""),
                            "error_code" to (ack.errorCode ?: "")
                        )
                        val eventRequest = com.iips.launcher.config.MdmEventRequest(
                            type = "COMMAND_ACK",
                            payload = payload
                        )
                        configService.sendEvent("Bearer $token", eventRequest)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to acknowledge via HTTP", e)
                }
            }
        }
    }



    private fun reconnect() {
        retryAttempt++
        val delay = (Math.pow(2.0, retryAttempt.toDouble()) * 1000).toLong().coerceAtMost(MAX_RETRY_DELAY)
        Log.d(TAG, "Reconnecting in ${delay/1000}s (Attempt $retryAttempt)")
        scope.launch {
            delay(delay)
            connect()
        }
    }

    private fun createNotification(): Notification {
        val channelId = "mdm_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "MDM Management", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Device Management Active")
            .setContentText("Secured MDM Connection")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        pollingHandler.removeCallbacks(pollingRunnable)
        webSocket?.close(1000, "Service Destroyed")
        scope.cancel()
    }
}
