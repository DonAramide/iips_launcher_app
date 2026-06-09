package com.iips.launcher.ota

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.iips.launcher.security.PackageSignatureValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApkInstallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val signatureValidator: PackageSignatureValidator
) {
    companion object {
        private const val TAG = "ApkInstallManager"
        const val ACTION_INSTALL_COMPLETE = "com.iips.launcher.INSTALL_COMPLETE"
    }

    /**
     * Installs an APK silently if it passes signature validation.
     */
    fun installApk(apkFile: File, appId: String? = null, version: String? = null): Boolean {
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file does not exist: ${apkFile.absolutePath}")
            return false
        }

        // 1. Validate Signature
        if (!signatureValidator.isApkTrusted(apkFile)) {
            Log.e(TAG, "APK signature validation failed. Aborting installation.")
            return false
        }

        // 2. Perform Silent Installation
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            
            // Set App Package Name if parsable
            try {
                val packageInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                packageInfo?.packageName?.let {
                    params.setAppPackageName(it)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve package name from APK archive", e)
            }

            // Request silent install (no user action) on Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            FileInputStream(apkFile).use { input ->
                session.openWrite("package_install", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            val intent = Intent(context, com.iips.launcher.core.BootReceiver::class.java).apply {
                action = ACTION_INSTALL_COMPLETE
                putExtra("package_path", apkFile.absolutePath)
                if (appId != null) putExtra("app_id", appId)
                if (version != null) putExtra("version", version)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )

            session.commit(pendingIntent.intentSender)
            session.close()
            Log.i(TAG, "Install session $sessionId committed for ${apkFile.name}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Silent install failed", e)
            return false
        }
    }
}
