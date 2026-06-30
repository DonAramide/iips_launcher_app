 package com.iips.launcher.policy

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
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class GeofenceService : Service() {

    @Inject
    lateinit var geofenceManager: GeofenceManager

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
    }

    private fun setupLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(2000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    processLocation(location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
        }
    }

    private fun processLocation(location: Location) {
        serviceScope.launch {
            if (SecurePreferences.getDeviceState(this@GeofenceService) != SecurePreferences.STATE_ACTIVE) return@launch
            
            val isSpoofed = geofenceManager.isLocationSpoofed(this@GeofenceService, location)
            val shouldLock = geofenceManager.shouldLockDevice(this@GeofenceService, location)
            val isCurrentlyLocked = SecurePreferences.isGeofenceLocked(this@GeofenceService)
            
            if (isSpoofed || shouldLock) {
                if (!isCurrentlyLocked) {
                    SecurePreferences.setGeofenceLocked(this@GeofenceService, true)
                    
                    val closest = geofenceManager.getClosestZone(this@GeofenceService, location)
                    broadcastLockStatus(true, if (isSpoofed) "Mock location detected" else "Outside authorized area", location, closest)
                }
                geofenceManager.reportStatus(this@GeofenceService, location, false)
            } else {
                if (isCurrentlyLocked) {
                    SecurePreferences.setGeofenceLocked(this@GeofenceService, false)
                    broadcastLockStatus(false)
                }
                geofenceManager.reportStatus(this@GeofenceService, location, true)
            }
        }
    }

    private fun broadcastLockStatus(locked: Boolean, reason: String? = null, location: Location? = null, closestZone: com.iips.launcher.network.models.GeofenceRule? = null) {
        val intent = Intent(if (locked) ACTION_GEOFENCE_LOCK else ACTION_GEOFENCE_UNLOCK)
        intent.putExtra(EXTRA_REASON, reason)
        if (location != null) {
            intent.putExtra("EXTRA_LAT", location.latitude)
            intent.putExtra("EXTRA_LNG", location.longitude)
        }
        if (closestZone != null) {
            intent.putExtra("EXTRA_CLOSEST_LAT", closestZone.lat)
            intent.putExtra("EXTRA_CLOSEST_LNG", closestZone.lng)
            intent.putExtra("EXTRA_RADIUS", closestZone.radius_m)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Geofence Monitoring Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, LauncherActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dotroid Security Active")
            .setContentText("Monitoring device location")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
    }
}
