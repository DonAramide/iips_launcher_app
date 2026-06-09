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
        // The trusted developer certificate hash for IIPS/Dotoid (from test_release.keystore)
        private const val TRUSTED_DEVELOPER_HASH = "7A:9F:F2:0E:BD:28:39:A2:5F:65:FF:A4:BF:EF:A7:08:1E:BA:10:76:61:7E:D4:DD:78:CA:C2:B7:68:F1:D3:2D" 
    }

    /**
     * Verifies if the APK file is signed with a trusted certificate.
     */
    fun isApkTrusted(apkFile: File): Boolean {
        try {
            @Suppress("DEPRECATION")
            val flags = PackageManager.GET_SIGNATURES
            val packageInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            
            @Suppress("DEPRECATION")
            var signatures = packageInfo?.signatures

            if (signatures.isNullOrEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val modernInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                signatures = modernInfo?.signingInfo?.apkContentsSigners
            }

            if (signatures.isNullOrEmpty()) {
                Log.e(TAG, "No signatures found in APK: ${apkFile.absolutePath}")
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
            if (com.iips.launcher.BuildConfig.DEBUG) {
                Log.w(TAG, "DEVELOPMENT FALLBACK: Allowing signature mismatch in debug mode.")
                return true
            }
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
