package com.iips.launcher.pocket.service

import android.app.*
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.storage.StorageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.iips.launcher.data.AppDatabase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class AppHealthMonitoringService : Service() {

    companion object {
        private const val TAG = "AppHealthMonitor"
        private const val NOTIF_ID = 3003
        private const val NOTIF_CHANNEL_ID = "health_monitor"
        private const val SCAN_INTERVAL_MS = 60000L // Scan every minute

        fun start(context: Context) {
            val intent = Intent(context, AppHealthMonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    @Inject
    lateinit var database: AppDatabase

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scanJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        startHealthScanning()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        scanJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startHealthScanning() {
        scanJob = scope.launch {
            while (isActive) {
                try {
                    performHealthScan()
                } catch (e: Exception) {
                    Log.e(TAG, "Error performing health scan", e)
                }
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    private suspend fun performHealthScan() {
        val pocketDao = database.appPocketDao()
        val apps = pocketDao.getAllApps()
        val pm = packageManager
        
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        val storageStatsManager = getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager

        for (app in apps) {
            var isInstalled = false
            var lastUsed: Long? = app.lastUsedTimestamp
            var storageBytes = app.storageUsageBytes

            try {
                val info = pm.getPackageInfo(app.packageName, 0)
                isInstalled = true
                
                if (storageStatsManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        val stats = storageStatsManager.queryStatsForPackage(
                            StorageManager.UUID_DEFAULT,
                            app.packageName,
                            Process.myUserHandle()
                        )
                        storageBytes = stats.appBytes + stats.dataBytes + stats.cacheBytes
                    } catch (e: Exception) {
                        app.apkPath?.let { path ->
                            val f = File(path)
                            if (f.exists()) storageBytes = f.length()
                        }
                    }
                }

                if (usageStatsManager != null) {
                    val endTime = System.currentTimeMillis()
                    val startTime = endTime - 1000 * 60 * 60 * 24 // Last 24 hours
                    val stats = usageStatsManager.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY,
                        startTime,
                        endTime
                    )
                    val appStats = stats.find { it.packageName == app.packageName }
                    if (appStats != null) {
                        lastUsed = appStats.lastTimeUsed
                    }
                }
            } catch (e: PackageManager.NameNotFoundException) {
                isInstalled = false
            }

            val health = when {
                app.isRequired && !isInstalled -> "CRITICAL"
                app.crashCount > 5 -> "WARNING"
                storageBytes > 500 * 1024 * 1024 -> "WARNING"
                else -> "HEALTHY"
            }

            val updated = app.copy(
                status = if (isInstalled) "INSTALLED" else app.status,
                isMissing = app.isRequired && !isInstalled,
                storageUsageBytes = storageBytes,
                lastUsedTimestamp = lastUsed,
                healthStatus = health
            )

            if (updated != app) {
                pocketDao.insertApp(updated)
                Log.d(TAG, "Updated health stats for package: ${app.packageName} (health: $health)")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "App Health Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("App Health Monitor Active")
            .setContentText("Monitoring storage, crashes, and usage stats.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
