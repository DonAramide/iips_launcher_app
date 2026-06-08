package com.iips.launcher.pocket.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.iips.launcher.data.AppDatabase
import com.iips.launcher.pocket.data.AppPocketEntity
import com.iips.launcher.pocket.data.AppPocketAuditEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@AndroidEntryPoint
class ApkDirectoryObserverService : Service() {

    companion object {
        private const val TAG = "ApkDirObserver"
        private const val NOTIF_ID = 3002
        private const val NOTIF_CHANNEL_ID = "apk_observer"
        
        fun start(context: Context) {
            val intent = Intent(context, ApkDirectoryObserverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    @Inject
    lateinit var database: AppDatabase

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var downloadObserver: FileObserver? = null
    private var dotroidObserver: FileObserver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        setupObservers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        downloadObserver?.stopWatching()
        dotroidObserver?.stopWatching()
        scope.cancel()
        super.onDestroy()
    }

    private fun setupObservers() {
        val downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadPath.exists()) downloadPath.mkdirs()

        val dotroidPath = File(Environment.getExternalStorageDirectory(), "Dotroid/AppPocket")
        if (!dotroidPath.exists()) dotroidPath.mkdirs()

        downloadObserver = createObserver(downloadPath)
        dotroidObserver = createObserver(dotroidPath)

        downloadObserver?.startWatching()
        dotroidObserver?.startWatching()
        
        Log.i(TAG, "Watching paths for APK ingestion: ${downloadPath.absolutePath} and ${dotroidPath.absolutePath}")
    }

    private fun createObserver(directory: File): FileObserver {
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(directory, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && path.endsWith(".apk")) {
                        scope.launch {
                            delay(1000) // Small delay to ensure file is fully unlocked
                            ingestApkFile(File(directory, path))
                        }
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(directory.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && path.endsWith(".apk")) {
                        scope.launch {
                            delay(1000)
                            ingestApkFile(File(directory, path))
                        }
                    }
                }
            }
        }
    }

    private fun ingestApkFile(apkFile: File) {
        if (!apkFile.exists() || !apkFile.name.endsWith(".apk")) return

        Log.d(TAG, "Ingesting APK file: ${apkFile.absolutePath}")
        val pm = packageManager
        val info = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
        if (info == null) {
            Log.w(TAG, "Could not parse archive info for: ${apkFile.absolutePath}")
            return
        }

        val appInfo = info.applicationInfo
        appInfo.sourceDir = apkFile.absolutePath
        appInfo.publicSourceDir = apkFile.absolutePath
        val appName = appInfo.loadLabel(pm).toString()
        val packageName = info.packageName
        val versionName = info.versionName ?: "1.0"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

        var iconCachePath: String? = null
        try {
            val iconDrawable = appInfo.loadIcon(pm)
            val bitmap = if (iconDrawable is android.graphics.drawable.BitmapDrawable) {
                iconDrawable.bitmap
            } else {
                val width = iconDrawable.intrinsicWidth.coerceAtLeast(1)
                val height = iconDrawable.intrinsicHeight.coerceAtLeast(1)
                val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                iconDrawable.setBounds(0, 0, canvas.width, canvas.height)
                iconDrawable.draw(canvas)
                bmp
            }

            val iconDir = File(cacheDir, "app_pocket_icons")
            if (!iconDir.exists()) iconDir.mkdirs()
            val cachedIconFile = File(iconDir, "$packageName.png")
            FileOutputStream(cachedIconFile).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            iconCachePath = cachedIconFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error caching application icon", e)
        }

        scope.launch(Dispatchers.IO) {
            var isInstalled = false
            var currentVersionCode = 0L
            try {
                val installedInfo = pm.getPackageInfo(packageName, 0)
                isInstalled = true
                currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    installedInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    installedInfo.versionCode.toLong()
                }
            } catch (e: PackageManager.NameNotFoundException) {
                // Not installed
            }

            val appPocketDao = database.appPocketDao()
            val existing = appPocketDao.getApp(packageName)

            val status = when {
                isInstalled && versionCode > currentVersionCode -> "UPDATE_AVAILABLE"
                isInstalled -> "INSTALLED"
                existing?.status == "APPROVED" -> "APPROVED"
                existing?.status == "AWAITING_APPROVAL" -> "AWAITING_APPROVAL"
                else -> "PENDING"
            }

            val appType = if (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) "SYSTEM" else "USER"

            val appEntity = AppPocketEntity(
                packageName = packageName,
                appName = appName,
                versionName = versionName,
                versionCode = versionCode,
                apkPath = apkFile.absolutePath,
                iconCachePath = iconCachePath,
                installDate = existing?.installDate,
                uninstallDate = existing?.uninstallDate,
                source = "FILE_TRANSFER",
                status = status,
                appType = appType,
                isRequired = existing?.isRequired ?: false,
                isMissing = existing?.isMissing ?: false,
                lastUsedTimestamp = existing?.lastUsedTimestamp,
                storageUsageBytes = apkFile.length(),
                crashCount = existing?.crashCount ?: 0,
                healthStatus = existing?.healthStatus ?: "HEALTHY",
                updateAvailableVersion = if (status == "UPDATE_AVAILABLE") versionName else existing?.updateAvailableVersion,
                addedAt = System.currentTimeMillis()
            )

            appPocketDao.insertApp(appEntity)

            appPocketDao.insertAudit(
                AppPocketAuditEntity(
                    packageName = packageName,
                    appName = appName,
                    versionName = versionName,
                    versionCode = versionCode,
                    actionType = "APK_INTERCEPTED",
                    userId = null
                )
            )
            Log.i(TAG, "Ingested $packageName version $versionName into App Pocket registry.")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "App Pocket Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("App Pocket Monitor Active")
            .setContentText("Monitoring directories for APK files.")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
