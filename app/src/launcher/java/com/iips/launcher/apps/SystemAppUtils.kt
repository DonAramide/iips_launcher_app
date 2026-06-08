package com.iips.launcher.apps

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.iips.launcher.policy.DeviceAdminReceiver

/**
 * System App Utilities
 * Provides utilities for system app features and verification
 */
object SystemAppUtils {
    
    private const val TAG = "SystemAppUtils"
    
    /**
     * Check if app is installed as system app
     */
    fun isSystemApp(context: Context): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES
            )
            
            // Check if app is installed to /system partition
            val applicationInfo = context.applicationInfo
            val isSystemApp = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                             (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            
            // Also check if running with system UID
            val isSystemUid = applicationInfo.uid < 10000 // System apps have UID < 10000
            
            isSystemApp || isSystemUid
        } catch (e: Exception) {
            Log.e(TAG, "Error checking system app status", e)
            false
        }
    }
    
    /**
     * Check if app has system UID (android.uid.system)
     */
    fun hasSystemUid(context: Context): Boolean {
        return try {
            val applicationInfo = context.applicationInfo
            val systemUid = android.os.Process.SYSTEM_UID // Usually 1000
            applicationInfo.uid == systemUid
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get installation path of the app
     */
    fun getInstallationPath(context: Context): String? {
        return try {
            val packageManager = context.packageManager
            val packageInfo = packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.applicationInfo.sourceDir
        } catch (e: Exception) {
            Log.e(TAG, "Error getting installation path", e)
            null
        }
    }
    
    /**
     * Check if app is installed to /system partition
     */
    fun isInstalledToSystem(context: Context): Boolean {
        val path = getInstallationPath(context)
        return path != null && (path.startsWith("/system/") || path.startsWith("/system_root/system/"))
    }
    
    /**
     * Get comprehensive app status (for diagnostics)
     */
    fun getAppStatus(context: Context): AppStatus {
        return AppStatus(
            isSystemApp = isSystemApp(context),
            hasSystemUid = hasSystemUid(context),
            isDeviceOwner = DeviceAdminReceiver.isDeviceOwner(context),
            isInstalledToSystem = isInstalledToSystem(context),
            installationPath = getInstallationPath(context)
        )
    }
    
    /**
     * Check if app can survive factory reset
     * Only true if installed to /system partition
     */
    fun canSurviveFactoryReset(context: Context): Boolean {
        return isInstalledToSystem(context)
    }
    
    /**
     * App status data class
     */
    data class AppStatus(
        val isSystemApp: Boolean,
        val hasSystemUid: Boolean,
        val isDeviceOwner: Boolean,
        val isInstalledToSystem: Boolean,
        val installationPath: String?
    ) {
        fun isFullyProtected(): Boolean {
            return isInstalledToSystem && isDeviceOwner
        }
    }
}




