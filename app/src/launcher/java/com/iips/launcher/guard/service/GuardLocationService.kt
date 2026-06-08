package com.iips.launcher.guard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.iips.launcher.guard.data.*
import com.iips.launcher.guard.policy.GeofenceEvaluator
import com.iips.launcher.guard.policy.GuardState
import com.iips.launcher.guard.security.MockLocationDetector
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class GuardLocationService : Service() {

    @Inject
    lateinit var guardEventDao: GuardEventDao

    @Inject
    lateinit var locationReportDao: LocationReportDao

    @Inject
    lateinit var geofenceRuleDao: GeofenceRuleDao

    @Inject
    lateinit var trackingSessionDao: TrackingSessionDao

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val geofenceEvaluator = GeofenceEvaluator()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var providerStatusReceiver: ProviderStatusReceiver? = null
    private var networkMonitor: NetworkMonitor? = null
    private var activeSessionId: String? = null

    companion object {
        private const val TAG = "GuardLocationService"
        private const val CHANNEL_ID = "guard_location_service"
        private const val NOTIFICATION_ID = 2004

        fun start(context: Context) {
            val intent = Intent(context, GuardLocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GuardLocationService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Creating GuardLocationService")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    handleLocation(location)
                }
            }
        }

        registerProviderReceiver()
        startNetworkMonitoring()
        requestLocationUpdates()
        restoreActiveSession()
    }

    private fun registerProviderReceiver() {
        providerStatusReceiver = ProviderStatusReceiver { isEnabled ->
            val eventType = if (isEnabled) "GPS_ENABLED" else "GPS_DISABLED"
            Log.d(TAG, "GPS Status event detected: $eventType")
            logSecurityEvent(eventType, "GPS hardware provider changed state")
        }
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        registerReceiver(providerStatusReceiver, filter)
    }

    private fun startNetworkMonitoring() {
        networkMonitor = NetworkMonitor(this) { isOnline ->
            val eventType = if (isOnline) "DEVICE_ONLINE" else "DEVICE_OFFLINE"
            Log.d(TAG, "Network status change detected: $eventType")
            logSecurityEvent(eventType, "Device internet status changed")
            if (isOnline) {
                com.iips.launcher.guard.worker.LocationSyncWorker.schedule(this)
            }
        }
        networkMonitor?.startMonitoring()
    }

    private fun requestLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30000) // 30s
            .setMinUpdateIntervalMillis(15000) // 15s
            .setMaxUpdateDelayMillis(60000) // 60s
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing location permission: ${e.message}")
        }
    }

    private fun restoreActiveSession() {
        serviceScope.launch {
            val session = trackingSessionDao.getActiveSession()
            if (session != null) {
                activeSessionId = session.sessionId
                Log.d(TAG, "Restored active tracking session: $activeSessionId")
            }
        }
    }

    private fun handleLocation(location: Location) {
        Log.d(TAG, "Location received: lat=${location.latitude}, lng=${location.longitude}, acc=${location.accuracy}")
        
        val isMock = MockLocationDetector.isMockLocation(location)
        if (isMock) {
            Log.w(TAG, "Mock location detected!")
            logSecurityEvent("MOCK_LOCATION", "Mock provider detected: ${location.provider}")
            transitionToState(GuardState.LOCKED, "GPS Tampering/Mock Location Detected")
        }

        serviceScope.launch {
            val rules = geofenceRuleDao.getAllRules()
            val savedStateStr = SecurePreferences.getGuardState(this@GuardLocationService)
            val currentState = try {
                GuardState.valueOf(savedStateStr)
            } catch (e: Exception) {
                GuardState.NORMAL
            }

            val nextState = geofenceEvaluator.evaluate(location, rules, currentState)
            
            // Save location report linked to session
            val sessionIdToLink = if (nextState != GuardState.NORMAL) {
                getOrCreateSessionId()
            } else {
                closeActiveSession()
                null
            }

            val report = LocationReportEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionIdToLink,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy,
                speed = location.speed,
                timestamp = System.currentTimeMillis() / 1000,
                isMock = isMock,
                isSynced = false
            )
            locationReportDao.insertLocation(report)

            if (nextState != currentState) {
                transitionToState(nextState, "Geofence Evaluation State Shift")
            }
        }
    }

    private fun transitionToState(nextState: GuardState, reason: String) {
        serviceScope.launch {
            val currentStateStr = SecurePreferences.getGuardState(this@GuardLocationService)
            Log.i(TAG, "State Transition: $currentStateStr -> $nextState (Reason: $reason)")
            SecurePreferences.setGuardState(this@GuardLocationService, nextState.name)

            when (nextState) {
                GuardState.NORMAL -> {
                    SecurePreferences.setGeofenceLocked(this@GuardLocationService, false)
                    closeActiveSession()
                    logSecurityEvent("GEOFENCE_REENTRY", "Device re-entered zone")
                    logSecurityEvent("DEVICE_UNLOCKED", "Launcher state unlocked")
                    sendBroadcast(Intent(com.iips.launcher.policy.GeofenceService.ACTION_GEOFENCE_UNLOCK))
                }
                GuardState.VIOLATION_PENDING -> {
                    // Pre-alert state, no lock yet
                }
                GuardState.LOCKED -> {
                    SecurePreferences.setGeofenceLocked(this@GuardLocationService, true)
                    getOrCreateSessionId()
                    logSecurityEvent("GEOFENCE_EXIT", "Device left geofence boundaries")
                    logSecurityEvent("DEVICE_LOCKED", "Launcher state locked: $reason")
                    sendBroadcast(Intent(com.iips.launcher.policy.GeofenceService.ACTION_GEOFENCE_LOCK).apply {
                        putExtra(com.iips.launcher.policy.GeofenceService.EXTRA_REASON, reason)
                    })
                }
                GuardState.RECOVERY_PENDING -> {
                    // State awaiting consecutive inside entries or recovery inputs
                }
            }
        }
    }

    private suspend fun getOrCreateSessionId(): String {
        activeSessionId?.let { return it }
        val newId = UUID.randomUUID().toString()
        val session = TrackingSessionEntity(
            sessionId = newId,
            startTime = System.currentTimeMillis() / 1000,
            endTime = null,
            isActive = true
        )
        trackingSessionDao.insertSession(session)
        activeSessionId = newId
        Log.d(TAG, "Started new tracking session: $newId")
        return newId
    }

    private suspend fun closeActiveSession() {
        activeSessionId?.let { id ->
            trackingSessionDao.closeSession(id, System.currentTimeMillis() / 1000)
            Log.d(TAG, "Closed tracking session: $id")
            activeSessionId = null
        }
    }

    private fun logSecurityEvent(type: String, details: String?) {
        serviceScope.launch {
            val event = SecurityEventEntity(
                id = UUID.randomUUID().toString(),
                eventType = type,
                timestamp = System.currentTimeMillis() / 1000,
                payloadJson = details,
                isSynced = false
            )
            guardEventDao.insertEvent(event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Destroying GuardLocationService")
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove location updates: ${e.message}")
        }
        providerStatusReceiver?.let {
            unregisterReceiver(it)
        }
        networkMonitor?.stopMonitoring()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Guard Location Monitoring",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dotroid Guard Active")
            .setContentText("Monitoring device security zones...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
