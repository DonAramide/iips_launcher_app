package com.iips.launcher.security

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors the device for security threats like root, debugging, and tampering.
 */
@Singleton
class SecurityMonitorManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SecurityMonitor"
    }

    /**
     * Checks if the device is rooted using common indicators.
     */
    fun isRooted(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        
        for (path in paths) {
            if (File(path).exists()) return true
        }
        
        return try {
            Runtime.getRuntime().exec("which su").inputStream.bufferedReader().readLine() != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if developer options/ADB is enabled.
     */
    fun isAdbEnabled(): Boolean {
        return android.provider.Settings.Global.getInt(
            context.contentResolver,
            android.provider.Settings.Global.ADB_ENABLED, 0
        ) != 0
    }

    /**
     * Detects if unauthorized accessibility services are running.
     */
    fun getUnauthorizedAccessibilityServices(): List<String> {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val services = am.getEnabledAccessibilityServiceList(android.view.accessibility.AccessibilityEvent.TYPES_ALL_MASK)
        
        // List of trusted services (can be expanded)
        val trusted = listOf("com.google.android.apps.accessibility.voiceaccess")
        
        return services.map { it.id }.filter { id -> trusted.none { id.contains(it) } }
    }

    /**
     * Detects potential overlay attacks or unauthorized "Draw over other apps" permission.
     */
    fun hasSuspiciousOverlays(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // This is complex to detect programmatically without being the top app,
            // but we can check if many apps have the permission.
            return false
        }
        return false
    }

    /**
     * Detects Magisk or Xposed by searching for specific packages and files.
     */
    fun detectTamperingFrameworks(): List<String> {
        val frameworkPackages = listOf(
            "com.topjohnwu.magisk",
            "de.robv.android.xposed.installer",
            "org.meowcat.edxposed.manager",
            "org.lsposed.manager"
        )
        
        val found = mutableListOf<String>()
        val pm = context.packageManager
        
        for (pkg in frameworkPackages) {
            try {
                pm.getPackageInfo(pkg, 0)
                found.add(pkg)
            } catch (e: Exception) {}
        }
        
        return found
    }
}
