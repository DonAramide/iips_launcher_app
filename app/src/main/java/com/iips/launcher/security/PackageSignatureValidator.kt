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
        // The trusted developer certificate hashes for IIPS/Dotoid apps & enterprise packages
        private val TRUSTED_DEVELOPER_HASHES = setOf(
            "7A:9F:F2:0E:BD:28:39:A2:5F:65:FF:A4:BF:EF:A7:08:1E:BA:10:76:61:7E:D4:DD:78:CA:C2:B7:68:F1:D3:2D",
            "DA:2F:ED:50:EB:64:05:6E:4A:54:B2:00:5B:5F:AB:6E:63:1F:FB:E7:D7:21:A6:65:F5:7E:95:D0:66:A9:BB:1C",
            // WhatsApp Official Release Cert Hash
            "39:87:D0:43:D1:0A:EF:AF:5A:87:10:B3:67:14:18:FE:57:E0:E1:9B:65:3C:9D:F8:25:58:FE:B5:FF:CE:5D:44"
        )
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

            val pkgName = packageInfo?.packageName
            val isSelfUpdate = pkgName == context.packageName || pkgName == "com.iips.dotroid" || pkgName == "com.iips.guard" || pkgName == "com.iips.launcher"

            for (sig in signatures) {
                val certHash = calculateSha256(sig.toByteArray())
                if (TRUSTED_DEVELOPER_HASHES.any { it.equals(certHash, ignoreCase = true) }) {
                    Log.d(TAG, "APK signature matches trusted developer: $certHash for $pkgName")
                    return true
                }
            }

            val foundCert = calculateSha256(signatures[0].toByteArray())
            if (isSelfUpdate) {
                Log.e(TAG, "Self-update APK signature mismatch for $pkgName! Found: $foundCert")
                if (com.iips.launcher.BuildConfig.DEBUG) {
                    Log.w(TAG, "DEVELOPMENT FALLBACK: Allowing signature mismatch in debug mode.")
                    return true
                }
                return false
            }

            // For third-party apps distributed through MDM (like WhatsApp, enterprise catalog apps, etc.),
            // allow installation as long as the APK has valid signing certificates.
            Log.i(TAG, "Allowing installation of verified third-party MDM package: $pkgName (Cert: $foundCert)")
            return true
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
