package com.iips.launcher.ota

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * Handles downloading and silent installation of APKs.
 * Requires Device Owner privileges.
 */
object AppInstaller {
    private const val TAG = "AppInstaller"

    suspend fun installApk(
        context: Context, 
        apkUrl: String, 
        expectedChecksum: String? = null,
        appId: String? = null,
        version: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting APK download: $apkUrl")
            val tempFile = File(context.cacheDir, "update.apk")
            downloadFile(apkUrl, tempFile)
            
            // Verify Checksum if provided
            if (expectedChecksum != null) {
                val actualChecksum = com.iips.launcher.security.SecurityUtils.calculateFileSha256(tempFile)
                if (!actualChecksum.equals(expectedChecksum, ignoreCase = true)) {
                    Log.e(TAG, "Checksum mismatch! Expected: $expectedChecksum, Actual: $actualChecksum")
                    tempFile.delete()
                    if (appId != null) reportFailure(context, appId, version ?: "unknown", "Checksum mismatch")
                    return@withContext false
                }
                Log.d(TAG, "Checksum verified successfully")
            }

            Log.d(TAG, "APK downloaded, starting silent install")
            silentInstall(context, tempFile, appId, version)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK", e)
            if (appId != null) reportFailure(context, appId, version ?: "unknown", e.message ?: "Download/install failed")
            false
        }
    }

    private fun reportFailure(context: Context, appId: String, version: String, error: String) {
        val data = androidx.work.Data.Builder()
            .putString(com.iips.launcher.workers.InstallReportingWorker.KEY_APP_ID, appId)
            .putString(com.iips.launcher.workers.InstallReportingWorker.KEY_VERSION, version)
            .putString(com.iips.launcher.workers.InstallReportingWorker.KEY_STATUS, "FAILED")
            .putString(com.iips.launcher.workers.InstallReportingWorker.KEY_ERROR_MESSAGE, error)
            .build()
            
        val request = androidx.work.OneTimeWorkRequestBuilder<com.iips.launcher.workers.InstallReportingWorker>()
            .setInputData(data)
            .build()
            
        androidx.work.WorkManager.getInstance(context).enqueue(request)
    }

    private fun downloadFile(url: String, outputFile: File) {
        URL(url).openStream().use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun silentInstall(context: Context, apkFile: File, appId: String?, version: String?) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        
        apkFile.inputStream().use { input ->
            session.openWrite("package", 0, apkFile.length()).use { output ->
                input.copyTo(output)
                session.fsync(output)
            }
        }

        val intent = Intent(context, com.iips.launcher.core.BootReceiver::class.java).apply {
            action = "com.iips.launcher.INSTALL_COMPLETE"
            if (appId != null) {
                putExtra("app_id", appId)
                putExtra("version", version ?: "unknown")
            }
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        session.commit(pendingIntent.intentSender)
        session.close()
        Log.d(TAG, "Install session committed")
    }

    suspend fun silentUninstall(context: Context, packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val intent = Intent(context, com.iips.launcher.core.BootReceiver::class.java).apply {
                action = "com.iips.launcher.UNINSTALL_COMPLETE"
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                System.currentTimeMillis().toInt(),
                intent,
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )

            packageInstaller.uninstall(packageName, pendingIntent.intentSender)
            Log.d(TAG, "Uninstall session requested for $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request uninstall", e)
            false
        }
    }
}
