package com.iips.launcher.security

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PackageSignatureValidator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "PackageSignatureValidator"
        // The trusted developer certificate hash for IIPS/Dotoid
        private const val TRUSTED_DEVELOPER_HASH = "8F:A1:75:5D:84:84:80:9A:8B:2F:9D:6A:9C:2C:1C:1E:1A:1D:1F:1B:1C:1D:1E:1F:20:21:22:23:24:25:26" 
    }

    /**
     * Verifies if the APK file is signed with a trusted certificate.
     */
    fun isApkTrusted(apkFile: File): Boolean {
        try {
            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
            }

            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo?.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo?.signatures
            }

            if (signatures.isNullOrEmpty()) {
                Log.e(TAG, "No signatures found in APK")
                return false
            }

            for (sig in signatures) {
                val certHash = calculateSha256(sig.toByteArray())
                if (certHash.equals(TRUSTED_DEVELOPER_HASH, ignoreCase = true)) {
                    Log.d(TAG, "APK signature matches trusted developer")
                    return true
                }
            }

            Log.e(TAG, "APK signature mismatch! Found: ${calculateSha256(signatures[0].toByteArray())}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Signature validation error", e)
            return false
        }
    }

    private fun calculateSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString(":") { "%02X".format(it) }
    }
}
