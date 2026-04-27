package com.iips.launcher.device

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.iips.launcher.R
import com.iips.launcher.ui.LauncherActivity
import com.iips.launcher.utils.SecurePreferences
import kotlinx.coroutines.*

/**
 * Foreground service for continuous geofence monitoring.
 */
class GeofenceService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    companion object {
        private const val TAG = "GeofenceService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "geofence_service_channel"
        
        const val ACTION_GEOFENCE_LOCK = "com.iips.launcher.ACTION_GEOFENCE_LOCK"
        const val ACTION_GEOFENCE_UNLOCK = "com.iips.launcher.ACTION_GEOFENCE_UNLOCK"
        const val EXTRA_REASON = "reason"

        fun start(context: Context) {
            val intent = Intent(context, GeofenceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GeofenceService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        setupLocationUpdates()
        
        // Initial config sync
        serviceScope.launch {
            GeofenceManager.syncConfig(this@GeofenceService)
        }
    }

    private fun setupLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60000L) // 60s
            .setMinUpdateIntervalMillis(60000L)
            .setMaxUpdateDelayMillis(120000L) // 120s max delay
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    processLocation(location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
        }
    }

    private fun processLocation(location: Location) {
        serviceScope.launch {
            val isSpoofed = GeofenceManager.isLocationSpoofed(this@GeofenceService, location)
            val shouldLock = GeofenceManager.shouldLockDevice(this@GeofenceService, location)
            
            val isCurrentlyLocked = SecurePreferences.isGeofenceLocked(this@GeofenceService)
            
            if (isSpoofed || shouldLock) {
                if (!isCurrentlyLocked) {
                    Log.w(TAG, "Device outside geofence or spoofed! Locking...")
                    SecurePreferences.setGeofenceLocked(this@GeofenceService, true)
                    broadcastLockStatus(true, if (isSpoofed) "Mock location detected" else "Outside authorized area")
                }
                GeofenceManager.reportStatus(this@GeofenceService, location, false)
            } else {
                if (isCurrentlyLocked) {
                    Log.i(TAG, "Device back inside geofence. Unlocking...")
                    SecurePreferences.setGeofenceLocked(this@GeofenceService, false)
                    broadcastLockStatus(false)
                }
                GeofenceManager.reportStatus(this@GeofenceService, location, true)
            }
        }
    }

    private fun broadcastLockStatus(locked: Boolean, reason: String? = null) {
        val intent = Intent(if (locked) ACTION_GEOFENCE_LOCK else ACTION_GEOFENCE_UNLOCK)
        intent.putExtra(EXTRA_REASON, reason)
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Geofence Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, LauncherActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IIPS Security Active")
            .setContentText("Monitoring device location")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
    }
}
