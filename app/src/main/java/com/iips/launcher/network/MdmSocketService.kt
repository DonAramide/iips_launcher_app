package com.iips.launcher.network

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
import com.iips.launcher.commands.CommandManager
import com.iips.launcher.network.models.CommandAcknowledgement
import com.iips.launcher.network.models.MdmCommand
import com.iips.launcher.network.models.MdmEventRequest
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class MdmSocketService : Service() {
    private val TAG = "MdmSocketService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private val gson = Gson()
    
    @Inject
    lateinit var commandManager: CommandManager

    @Inject
    lateinit var configService: ConfigService

    @Inject
    lateinit var okHttpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
        
        commandManager.startProcessor { ack ->
            acknowledge(ack)
        }
        
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun connect() {
        val token = SecurePreferences.getDeviceToken(this) ?: return
        val wsUrl = SecurePreferences.getWebSocketUrl(this)

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "MDM WebSocket Connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val command = gson.fromJson(text, MdmCommand::class.java)
                    commandManager.enqueueCommand(command)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse command", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "MDM WebSocket Failure: \${t.message}")
                reconnect()
            }
        })
    }

    private fun acknowledge(ack: CommandAcknowledgement) {
        scope.launch {
            if (ack.status == "SUCCESS" || ack.status == "FAILED") {
                SecurePreferences.setLastCommandId(this@MdmSocketService, ack.commandId)
                SecurePreferences.setLastCommandStatus(this@MdmSocketService, ack.status)
            }

            val json = gson.toJson(ack)
            val sentViaWs = webSocket?.send(json) == true
            
            if (!sentViaWs) {
                try {
                    val token = SecurePreferences.getDeviceToken(this@MdmSocketService)
                    if (token != null) {
                        val eventRequest = MdmEventRequest(
                            type = "COMMAND_ACK",
                            payload = mapOf(
                                "command_id" to ack.commandId,
                                "status" to ack.status,
                                "message" to (ack.message ?: ""),
                                "error_code" to (ack.errorCode ?: "")
                            )
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
        scope.launch {
            delay(10000)
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
        webSocket?.close(1000, "Service Destroyed")
        commandManager.stopProcessor()
        scope.cancel()
    }
}
