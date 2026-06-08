package com.iips.launcher.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enterprise-grade silent installer using the Android PackageInstaller API.
 * Requires Device Owner status for unattended installation.
 */
@Singleton
class SilentInstallEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SilentInstaller"
        const val ACTION_INSTALL_COMPLETE = "com.iips.launcher.INSTALL_COMPLETE"
    }

    /**
     * Performs a silent installation of an APK file.
     */
    fun installSilently(apkFile: File, packageName: String) {
        Log.i(TAG, "Starting silent install for \$packageName from \${apkFile.absolutePath}")
        
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        var sessionId = -1
        try {
            sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            
            val out = session.openWrite("package", 0, apkFile.length())
            val input = FileInputStream(apkFile)
            val buffer = ByteArray(65536)
            var n: Int
            while (input.read(buffer).also { n = it } >= 0) {
                out.write(buffer, 0, n)
            }
            
            session.fsync(out)
            input.close()
            out.close()
            
            val intent = Intent(context, SilentInstallReceiver::class.java).apply {
                action = ACTION_INSTALL_COMPLETE
                putExtra("package_name", packageName)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            
            session.commit(pendingIntent.intentSender)
            session.close()
            Log.d(TAG, "Install session \$sessionId committed for \$packageName")
            
        } catch (e: Exception) {
            Log.e(TAG, "Silent install failed for \$packageName", e)
            if (sessionId != -1) {
                try { installer.abandonSession(sessionId) } catch (ignored: Exception) {}
            }
        }
    }
}
