package com.iips.launcher.apps.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.iips.launcher.apps.inventory.AppInventoryRepository
import com.iips.launcher.apps.inventory.data.AppEventEntity
import com.iips.launcher.apps.inventory.data.AppInventoryDao
import com.iips.launcher.data.AppDatabase
import com.iips.launcher.guard.data.SecurityEventEntity
import com.iips.launcher.guard.worker.LocationSyncWorker
import com.iips.launcher.pocket.data.AppPocketEntity
import com.iips.launcher.pocket.data.AppPocketAuditEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class PackageMonitorReceiver : BroadcastReceiver() {
    private val TAG = "PackageMonitor"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Inject
    lateinit var appInventoryDao: AppInventoryDao

    @Inject
    lateinit var database: AppDatabase

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val packageName = intent.data?.schemeSpecificPart ?: return
        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

        Log.d(TAG, "Package Event: $action for $packageName (Replacing: $isReplacing)")

        scope.launch {
            // Native inventory tracking
            when (action) {
                Intent.ACTION_PACKAGE_ADDED -> {
                    if (!isReplacing) {
                        appInventoryDao.insertEvent(AppEventEntity(packageName = packageName, eventType = "INSTALLED"))
                    }
                }
                Intent.ACTION_PACKAGE_REMOVED -> {
                    if (!isReplacing) {
                        appInventoryDao.insertEvent(AppEventEntity(packageName = packageName, eventType = "REMOVED"))
                        appInventoryDao.delete(packageName)
                    }
                }
                Intent.ACTION_PACKAGE_REPLACED -> {
                    appInventoryDao.insertEvent(AppEventEntity(packageName = packageName, eventType = "UPDATED"))
                }
            }

            // App Pocket package-centric tracking
            val pocketDao = database.appPocketDao()
            val pm = context.packageManager

            when (action) {
                Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED -> {
                    try {
                        val info = pm.getPackageInfo(packageName, 0)
                        val appInfo = info.applicationInfo
                        val appName = appInfo.loadLabel(pm).toString()
                        val versionName = info.versionName ?: "1.0"
                        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            info.longVersionCode
                        } else {
                            @Suppress("DEPRECATION")
                            info.versionCode.toLong()
                        }
                        
                        val existing = pocketDao.getApp(packageName)
                        val appType = if (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) "SYSTEM" else "USER"
                        
                        val appEntity = AppPocketEntity(
                            packageName = packageName,
                            appName = appName,
                            versionName = versionName,
                            versionCode = versionCode,
                            apkPath = existing?.apkPath,
                            iconCachePath = existing?.iconCachePath,
                            installDate = System.currentTimeMillis(),
                            uninstallDate = existing?.uninstallDate,
                            source = existing?.source ?: "PRE_INSTALLED",
                            status = "INSTALLED",
                            appType = appType,
                            isRequired = existing?.isRequired ?: false,
                            isMissing = false,
                            lastUsedTimestamp = existing?.lastUsedTimestamp ?: System.currentTimeMillis(),
                            storageUsageBytes = existing?.storageUsageBytes ?: 0L,
                            crashCount = existing?.crashCount ?: 0,
                            healthStatus = "HEALTHY",
                            updateAvailableVersion = null,
                            addedAt = existing?.addedAt ?: System.currentTimeMillis()
                        )
                        
                        pocketDao.insertApp(appEntity)
                        pocketDao.insertAudit(
                            AppPocketAuditEntity(
                                packageName = packageName,
                                appName = appName,
                                versionName = versionName,
                                versionCode = versionCode,
                                actionType = "INSTALL_SUCCESS",
                                userId = null
                            )
                        )
                        Log.i(TAG, "App Pocket Registry updated: $packageName marked INSTALLED")
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.e(TAG, "Failed to retrieve package info on install", e)
                    }
                }
                
                Intent.ACTION_PACKAGE_REMOVED -> {
                    if (isReplacing) return@launch
                    val existing = pocketDao.getApp(packageName)
                    if (existing != null) {
                        val isRequired = existing.isRequired
                        val updatedStatus = "REMOVED"
                        val updatedHealth = if (isRequired) "CRITICAL" else "HEALTHY"
                        
                        val updatedEntity = existing.copy(
                            status = updatedStatus,
                            isMissing = isRequired,
                            healthStatus = updatedHealth,
                            uninstallDate = System.currentTimeMillis()
                        )
                        pocketDao.insertApp(updatedEntity)
                        
                        // Log Audit Trail
                        pocketDao.insertAudit(
                            AppPocketAuditEntity(
                                packageName = packageName,
                                appName = existing.appName,
                                versionName = existing.versionName,
                                versionCode = existing.versionCode,
                                actionType = if (isRequired) "REQUIRED_APP_REMOVED" else "UNINSTALLED",
                                userId = null
                            )
                        )
                        
                        // Handle Required App violation
                        if (isRequired) {
                            Log.w(TAG, "CRITICAL COMPLIANCE VIOLATION: Required App $packageName has been uninstalled!")
                            
                            val eventPayload = """
                                {
                                    "packageName": "$packageName",
                                    "appName": "${existing.appName}",
                                    "versionCode": ${existing.versionCode},
                                    "versionName": "${existing.versionName}",
                                    "uninstallDate": ${System.currentTimeMillis()}
                                }
                            """.trimIndent()
                            
                            val securityEvent = SecurityEventEntity(
                                id = UUID.randomUUID().toString(),
                                eventType = "REQUIRED_APP_UNINSTALLED",
                                timestamp = System.currentTimeMillis() / 1000L,
                                payloadJson = eventPayload,
                                isSynced = false
                            )
                            
                            database.guardEventDao().insertEvent(securityEvent)
                            
                            // Trigger immediate, high-priority background sync
                            LocationSyncWorker.startImmediateSync(context)
                        }
                    }
                }
            }
        }
    }
}
