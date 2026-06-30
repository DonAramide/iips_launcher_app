package com.iips.launcher.aai.inventory

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

data class InstalledAppMetadata(
    val packageName: String,
    val version: String,
    val versionCode: Long,
    val installTime: Long,
    val updateTime: Long,
    val enabled: Boolean,
    val systemApp: Boolean
)

class InstalledApplicationScanner(private val context: Context) {

    fun scanInstalledApps(): List<InstalledAppMetadata> {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
        val result = mutableListOf<InstalledAppMetadata>()

        for (pkg in packages) {
            val isSystemApp = (pkg.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isEnabled = pkg.applicationInfo.enabled
            
            result.add(
                InstalledAppMetadata(
                    packageName = pkg.packageName,
                    version = pkg.versionName ?: "Unknown",
                    versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) pkg.longVersionCode else pkg.versionCode.toLong(),
                    installTime = pkg.firstInstallTime,
                    updateTime = pkg.lastUpdateTime,
                    enabled = isEnabled,
                    systemApp = isSystemApp
                )
            )
        }
        return result
    }
}
