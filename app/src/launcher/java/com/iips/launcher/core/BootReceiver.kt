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
            val appId = intent.getStringExtra("app_id")
            val version = intent.getStringExtra("version") ?: "unknown"
            
            android.util.Log.i("BootReceiver", "Installation result for $appId: $status ($message)")
            
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
            // Check if Device Owner is set
            if (!DeviceAdminReceiver.isDeviceOwner(context)) {
                return // Not Device Owner, skip auto-start
            }
            
            // Auto-enable security features on boot (if previously enabled)
            if (SecurePreferences.isLockdownEnabled(context)) {
                // Security features will be enabled when LauncherActivity starts
            }
            
            // Auto-launch launcher if enabled as system app
            // This ensures launcher is always running
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
            
            // Apply comprehensive security after boot
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                DeviceController.enableComprehensiveSecurity(context)
            }

            // Start Guard service and sync worker if registered
            if (SecurePreferences.isRegistered(context)) {
                com.iips.launcher.guard.service.GuardLocationService.start(context)
                com.iips.launcher.guard.worker.LocationSyncWorker.schedule(context)
            }
            
        } catch (e: Exception) {
            // Ignore errors during boot (system may not be fully ready)
        }
    }
}