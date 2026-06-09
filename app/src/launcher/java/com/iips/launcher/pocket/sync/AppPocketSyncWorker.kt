package com.iips.launcher.pocket.sync

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.iips.launcher.data.AppDatabase
import com.iips.launcher.network.ConfigService
import com.iips.launcher.network.models.AppPocketAuditRequest
import com.iips.launcher.pocket.data.AppPocketEntity
import com.iips.launcher.storage.SecurePreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@HiltWorker
class AppPocketSyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val database: AppDatabase,
    private val configService: ConfigService
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "AppPocketSyncWorker"
        private const val UNIQUE_WORK_NAME = "com.iips.launcher.pocket.sync.APP_POCKET_SYNC"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<AppPocketSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<AppPocketSyncWorker>(
                1, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val token = SecurePreferences.getDeviceToken(context)
        if (token.isNullOrBlank()) {
            Log.w(TAG, "No device token available. Skipping sync.")
            return@withContext Result.failure()
        }

        val pocketDao = database.appPocketDao()
        val authHeader = "Bearer $token"

        try {
            val unsyncedAudits = pocketDao.getUnsyncedAudits()
            if (unsyncedAudits.isNotEmpty()) {
                val requests = unsyncedAudits.map {
                    AppPocketAuditRequest(
                        id = it.id,
                        packageName = it.packageName,
                        appName = it.appName,
                        versionName = it.versionName,
                        versionCode = it.versionCode,
                        actionType = it.actionType,
                        userId = it.userId,
                        timestamp = it.timestamp
                    )
                }
                val response = configService.reportAppPocketAudits(authHeader, requests)
                if (response.isSuccessful) {
                    pocketDao.markAuditsSynced(unsyncedAudits.map { it.id })
                    Log.i(TAG, "Successfully synced ${unsyncedAudits.size} audits.")
                } else {
                    Log.w(TAG, "Failed to sync audits: ${response.code()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing audits", e)
        }

        try {
            val response = configService.fetchAppCatalog(authHeader)
            if (response.isSuccessful) {
                val catalog = response.body() ?: emptyList()
                val pm = context.packageManager

                for (catalogApp in catalog) {
                    val resolvedPackageName = com.iips.launcher.storage.SecurePreferences.resolveMdmPackage(context, catalogApp.packageName)
                    val localApp = pocketDao.getApp(resolvedPackageName)
                    var isInstalled = false
                    var installedVersionCode = 0L
                    var installedVersionName = ""

                    try {
                        val pi = pm.getPackageInfo(resolvedPackageName, 0)
                        isInstalled = true
                        installedVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            pi.longVersionCode
                        } else {
                            pi.versionCode.toLong()
                        }
                        installedVersionName = pi.versionName ?: ""
                    } catch (e: PackageManager.NameNotFoundException) {
                        isInstalled = false
                    }

                    val hasUpdate = isInstalled && catalogApp.versionCode > installedVersionCode
                    val targetStatus = when {
                        isInstalled && hasUpdate -> "UPDATE_AVAILABLE"
                        isInstalled -> "INSTALLED"
                        localApp != null -> localApp.status
                        else -> "PENDING"
                    }

                    val updatedApp = AppPocketEntity(
                        packageName = resolvedPackageName,
                        appName = catalogApp.appName,
                        versionName = if (isInstalled && !hasUpdate) installedVersionName else catalogApp.versionName,
                        versionCode = if (isInstalled && !hasUpdate) installedVersionCode else catalogApp.versionCode,
                        apkPath = localApp?.apkPath,
                        iconCachePath = localApp?.iconCachePath,
                        installDate = localApp?.installDate,
                        uninstallDate = localApp?.uninstallDate,
                        source = localApp?.source ?: "REMOTE_DEPLOY",
                        status = targetStatus,
                        appType = catalogApp.appType,
                        isRequired = catalogApp.isRequired,
                        isMissing = catalogApp.isRequired && !isInstalled,
                        lastUsedTimestamp = localApp?.lastUsedTimestamp,
                        storageUsageBytes = localApp?.storageUsageBytes ?: 0L,
                        crashCount = localApp?.crashCount ?: 0,
                        healthStatus = localApp?.healthStatus ?: "HEALTHY",
                        updateAvailableVersion = if (hasUpdate) catalogApp.versionName else null,
                        addedAt = localApp?.addedAt ?: System.currentTimeMillis()
                    )

                    if (resolvedPackageName != catalogApp.packageName) {
                        pocketDao.deleteApp(catalogApp.packageName)
                    }
                    pocketDao.insertApp(updatedApp)
                }
                Log.i(TAG, "Successfully synced app catalog: ${catalog.size} items.")
            } else {
                Log.w(TAG, "Failed to fetch app catalog: ${response.code()}")
                return@withContext Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing catalog", e)
            return@withContext Result.retry()
        }

        Result.success()
    }
}
