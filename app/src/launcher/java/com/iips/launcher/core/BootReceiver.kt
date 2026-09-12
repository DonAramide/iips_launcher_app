package com.iips.launcher.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.iips.launcher.ui.LauncherActivity
import com.iips.launcher.policy.DeviceController
import com.iips.launcher.policy.DeviceAdminReceiver
import com.iips.launcher.storage.SecurePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Boot Receiver - Automatically starts launcher on device boot
 * This is especially useful for system apps installed to /system partition
 */
class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == Intent.ACTION_PACKAGE_REPLACED) {
            
            // Delay execution to ensure system is fully initialized
            Handler(Looper.getMainLooper()).postDelayed({
                handleBootComplete(context)
            }, 2000) // 2 second delay
        } else if (intent.action == "com.iips.launcher.INSTALL_COMPLETE") {
            val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1)
            val message = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
            val packageName = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_PACKAGE_NAME)
            val appId = intent.getStringExtra("app_id")
            val version = intent.getStringExtra("version") ?: "unknown"
            
            intent.extras?.keySet()?.forEach { key ->
                android.util.Log.d("BootReceiver", "Intent Extra: $key -> ${intent.extras?.get(key)}")
            }
            
            var resolvedPackageName = packageName
            if (resolvedPackageName == null) {
                val packagePath = intent.getStringExtra("package_path")
                if (packagePath != null) {
                    try {
                        val pm = context.packageManager
                        val info = pm.getPackageArchiveInfo(packagePath, 0)
                        resolvedPackageName = info?.packageName
                    } catch (e: Exception) {
                        android.util.Log.w("BootReceiver", "Failed to get package name from path: $packagePath", e)
                    }
                }
            }

            android.util.Log.i("BootReceiver", "Installation result for package $resolvedPackageName (appId $appId): status=$status, message=$message")
            
            if (status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (confirmIntent != null) {
                    android.util.Log.i("BootReceiver", "Launching user confirmation dialog for $resolvedPackageName")
                    context.startActivity(confirmIntent)
                }
            } else if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS && resolvedPackageName != null) {
                val db = com.iips.launcher.data.AppDatabase.getDatabase(context)
                CoroutineScope(Dispatchers.IO).launch {
                    val dao = db.appPocketDao()
                    
                    // Fallback: Check if the installation source package name was temporary/MDM-specific (e.g., "invifi")
                    var resolvedDbApp = dao.getApp(resolvedPackageName)
                    if (resolvedDbApp == null) {
                        val packagePath = intent.getStringExtra("package_path")
                        if (packagePath != null) {
                            val fileName = java.io.File(packagePath).name
                            if (fileName.startsWith("download_") && fileName.endsWith(".apk")) {
                                val dbPackageName = fileName.substringAfter("download_").substringBefore(".apk")
                                val tempApp = dao.getApp(dbPackageName)
                                if (tempApp != null) {
                                    // Save MDM mapping to resolve catalog syncing later
                                    com.iips.launcher.storage.SecurePreferences.saveMdmPackageMapping(context, tempApp.packageName, resolvedPackageName)
                                    // Delete the temporary/MDM package name entry to avoid duplicate items in the list
                                    dao.deleteApp(tempApp.packageName)
                                    android.util.Log.i("BootReceiver", "Deleted temporary MDM app entity for key: $dbPackageName")
                                    resolvedDbApp = tempApp.copy(packageName = resolvedPackageName)
                                }
                            }
                        }
                    }
                    
                    val currentApp = resolvedDbApp
                    if (currentApp != null) {
                        val pm = context.packageManager
                        var appName = currentApp.appName
                        var appType = currentApp.appType
                        var verName = currentApp.versionName
                        var verCode = currentApp.versionCode
                        try {
                            val info = pm.getPackageInfo(resolvedPackageName, 0)
                            appName = info.applicationInfo.loadLabel(pm).toString()
                            appType = if (info.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) "SYSTEM" else "USER"
                            verName = info.versionName ?: verName
                            verCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                info.longVersionCode
                            } else {
                                @Suppress("DEPRECATION")
                                info.versionCode.toLong()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("BootReceiver", "Failed to retrieve package info on successful install", e)
                        }

                        dao.insertApp(currentApp.copy(
                            status = "INSTALLED",
                            downloadStatus = "IDLE",
                            appName = appName,
                            appType = appType,
                            versionName = verName,
                            versionCode = verCode,
                            installDate = System.currentTimeMillis(),
                            isMissing = false,
                            healthStatus = "HEALTHY"
                        ))
                        android.util.Log.i("BootReceiver", "Updated AppPocket DB status to INSTALLED for $resolvedPackageName")
                    }
                }
            } else if (status != android.content.pm.PackageInstaller.STATUS_SUCCESS && resolvedPackageName != null) {
                val db = com.iips.launcher.data.AppDatabase.getDatabase(context)
                CoroutineScope(Dispatchers.IO).launch {
                    val dao = db.appPocketDao()
                    
                    // Fallback to filename mapping if not found by resolvedPackageName
                    var resolvedDbApp = dao.getApp(resolvedPackageName)
                    if (resolvedDbApp == null) {
                        val packagePath = intent.getStringExtra("package_path")
                        if (packagePath != null) {
                            val fileName = java.io.File(packagePath).name
                            if (fileName.startsWith("download_") && fileName.endsWith(".apk")) {
                                val dbPackageName = fileName.substringAfter("download_").substringBefore(".apk")
                                resolvedDbApp = dao.getApp(dbPackageName)
                            }
                        }
                    }
                    
                    val currentApp = resolvedDbApp
                    if (currentApp != null) {
                        dao.insertApp(currentApp.copy(
                            downloadStatus = "FAILED",
                            status = "DOWNLOADING"
                        ))
                        android.util.Log.i("BootReceiver", "Updated AppPocket DB status to FAILED for ${currentApp.packageName} due to installation failure.")
                    }
                }
            }

            if (appId != null) {
                val reportStatus = if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) "SUCCESS" else "FAILED"
                val data = androidx.work.Data.Builder()
                    .putString(com.iips.launcher.workers.InstallReportingWorker.KEY_APP_ID, appId)
                    .putString(com.iips.launcher.workers.InstallReportingWorker.KEY_VERSION, version)
                    .putString(com.iips.launcher.workers.InstallReportingWorker.KEY_STATUS, reportStatus)
                    .putString(com.iips.launcher.workers.InstallReportingWorker.KEY_ERROR_MESSAGE, message)
                    .build()
                    
                val request = androidx.work.OneTimeWorkRequestBuilder<com.iips.launcher.workers.InstallReportingWorker>()
                    .setInputData(data)
                    .build()
                    
                androidx.work.WorkManager.getInstance(context).enqueue(request)
            }
        } else if (intent.action == "com.iips.launcher.UNINSTALL_COMPLETE") {
            val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1)
            val packageName = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_PACKAGE_NAME)
            android.util.Log.i("BootReceiver", "Uninstallation result for $packageName: $status")
        }
    }
    
    private fun handleBootComplete(context: Context) {
        try {
            if (!DeviceAdminReceiver.isDeviceOwner(context)) {
                return
            }

            val state = SecurePreferences.getDeviceState(context)
            if (state == SecurePreferences.STATE_NEW || state == SecurePreferences.STATE_ONBOARDING) {
                return
            }

            val launchIntent = Intent(context, LauncherActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

            try {
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                // Ignore if launcher cannot start (may already be running)
            }

            val policyReady = SecurePreferences.isReadyForKioskSecurity(context)
            if (policyReady &&
                SecurePreferences.isLockdownEnabled(context) &&
                state == SecurePreferences.STATE_ACTIVE &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP
            ) {
                android.util.Log.i(
                    "BootReceiver",
                    "Boot kiosk apply: DeviceOwner=true EnrollmentComplete=true PolicyAvailable=true"
                )
                DeviceController.enableComprehensiveSecurity(context)
            } else {
                android.util.Log.i(
                    "BootReceiver",
                    "Boot kiosk skipped: DeviceOwner=true " +
                        "EnrollmentComplete=${SecurePreferences.isEnrollmentComplete(context)} " +
                        "Registered=${SecurePreferences.isRegistered(context)} " +
                        "PolicyAvailable=${SecurePreferences.hasValidPolicySnapshot(context)} " +
                        "state=$state"
                )
            }

            if (SecurePreferences.isRegistered(context)) {
                com.iips.launcher.guard.service.GuardLocationService.start(context)
                com.iips.launcher.guard.worker.LocationSyncWorker.schedule(context)
            }

        } catch (e: Exception) {
            // Ignore errors during boot (system may not be fully ready)
        }
    }
}