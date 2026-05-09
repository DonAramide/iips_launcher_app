package com.iips.launcher.apps.inventory

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.iips.launcher.apps.inventory.data.AppInventoryEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InstalledAppsScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "InstalledAppsScanner"
    }

    fun scanAllApps(): List<AppInventoryEntity> {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val inventory = mutableListOf<AppInventoryEntity>()

        for (app in apps) {
            try {
                val packageInfo = pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNATURES)
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                
                val signature = if (packageInfo.signatures != null && packageInfo.signatures.isNotEmpty()) {
                    calculateSha256(packageInfo.signatures[0].toByteArray())
                } else "none"

                val permissions = packageInfo.requestedPermissions?.joinToString(",") ?: ""

                inventory.add(AppInventoryEntity(
                    packageName = app.packageName,
                    appName = pm.getApplicationLabel(app).toString(),
                    versionName = packageInfo.versionName ?: "unknown",
                    versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode.toLong()
                    },
                    isSystemApp = isSystem,
                    installTime = packageInfo.firstInstallTime,
                    lastUpdateTime = packageInfo.lastUpdateTime,
                    signatureHash = signature,
                    permissions = permissions,
                    classification = if (isSystem) "SYSTEM" else "USER"
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning package ${app.packageName}", e)
            }
        }
        return inventory
    }

    private fun calculateSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
