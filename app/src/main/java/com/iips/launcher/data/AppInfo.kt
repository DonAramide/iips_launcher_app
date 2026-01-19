package com.iips.launcher.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val name: String,
    val icon: Drawable? = null,
    val isSystemApp: Boolean = false
) {
    companion object {
        fun fromApplicationInfo(
            applicationInfo: ApplicationInfo,
            packageManager: PackageManager
        ): AppInfo {
            return AppInfo(
                packageName = applicationInfo.packageName,
                name = packageManager.getApplicationLabel(applicationInfo).toString(),
                icon = packageManager.getApplicationIcon(applicationInfo),
                isSystemApp = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }
    }
}





