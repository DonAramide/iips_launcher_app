package com.iips.launcher.install

import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the APK installation pipeline: Validation -> Silent Install -> Monitoring.
 */
@Singleton
class PackageInstallManager @Inject constructor(
    private val verificationEngine: InstallVerificationEngine,
    private val silentInstallEngine: SilentInstallEngine,
    private val rollbackManager: InstallRollbackManager
) {
    companion object {
        private const val TAG = "PackageInstallManager"
    }

    /**
     * Entry point for installing an APK from a local file.
     */
    fun installPackage(apkFile: File, packageName: String, expectedHash: String? = null) {
        Log.i(TAG, "Request to install package: \$packageName")
        
        // 1. Validate
        if (!verificationEngine.validateApk(apkFile, packageName, expectedHash)) {
            Log.e(TAG, "Validation failed for \$packageName. Aborting install.")
            rollbackManager.handleFailure(packageName, -1, "Verification failed")
            return
        }

        // 2. Install Silently
        try {
            silentInstallEngine.installSilently(apkFile, packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Silent install initiation failed", e)
            rollbackManager.handleFailure(packageName, -1, e.message)
        }
    }
}
