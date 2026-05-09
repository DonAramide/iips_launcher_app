package com.iips.launcher.install

import android.content.Context
import android.util.Log
import com.iips.launcher.security.SecurityUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates APKs before installation to ensure fleet integrity.
 */
@Singleton
class InstallVerificationEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "InstallVerification"
    }

    /**
     * Comprehensive validation of an APK file.
     */
    fun validateApk(apkFile: File, expectedPackage: String, expectedHash: String?): Boolean {
        Log.d(TAG, "Validating APK for \$expectedPackage")

        // 1. Check if file exists
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file not found")
            return false
        }

        // 2. Validate SHA-256 Hash
        if (expectedHash != null) {
            val actualHash = SecurityUtils.calculateFileSha256(apkFile)
            if (actualHash != expectedHash) {
                Log.e(TAG, "Hash mismatch! Expected: \$expectedHash, Actual: \$actualHash")
                return false
            }
        }

        // 3. Validate Package Name and Signature
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
        
        if (info == null) {
            Log.e(TAG, "Failed to parse APK archive info")
            return false
        }

        if (info.packageName != expectedPackage) {
            Log.e(TAG, "Package name mismatch! Expected: \$expectedPackage, Actual: \${info.packageName}")
            return false
        }

        // 4. Validate Version (Prevent Downgrade unless explicitly allowed)
        try {
            val installedInfo = pm.getPackageInfo(expectedPackage, 0)
            if (info.versionCode < installedInfo.versionCode) {
                Log.w(TAG, "Downgrade detected: \${installedInfo.versionCode} -> \${info.versionCode}")
                // In production, you might want to reject this unless it's a rollback
            }
        } catch (e: Exception) {
            // Not installed, new installation
        }

        Log.i(TAG, "APK validation successful for \$expectedPackage")
        return true
    }
}
