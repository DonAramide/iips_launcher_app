package com.iips.launcher.kiosk

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.iips.launcher.ui.LauncherActivity
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * Foreground service that enforces the launcher as the foreground activity.
 * Detects unauthorized switches and kiosk escape attempts.
 */
@AndroidEntryPoint
class ForegroundEnforcementService : Service() {

    companion object {
        private const val TAG = "KioskEnforcement"
        private const val NOTIF_ID = 3001
        private const val NOTIF_CHANNEL_ID = "kiosk_enforcement"
        private const val CHECK_INTERVAL_MS = 2000L
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var monitoringJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startMonitoring() {
        monitoringJob = serviceScope.launch {
            while (isActive) {
                if (SecurePreferences.getKioskModeEnabled(this@ForegroundEnforcementService)) {
                    enforceLauncherForeground()
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private fun enforceLauncherForeground() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningTasks = am.getRunningTasks(1)
        
        if (runningTasks.isNotEmpty()) {
            val topActivity = runningTasks[0].topActivity
            val topPackage = topActivity?.packageName
            
            if (topPackage != packageName && topPackage != null) {
                // Check if the top package is allowed by policy
                val allowedApps = SecurePreferences.getAllowedApps(this)
                if (!allowedApps.contains(topPackage)) {
                    Log.w(TAG, "Unauthorized activity detected in foreground: \$topPackage. Returning to launcher.")
                    returnToLauncher()
                }
            }
        }
    }

    private fun returnToLauncher() {
        val intent = Intent(this, LauncherActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Kiosk Enforcement",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Kiosk Protection Active")
            .setContentText("Dotoid is securing this device.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
