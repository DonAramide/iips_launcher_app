package com.iips.launcher.apps.inventory

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
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
        val apps = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get installed applications", e)
            return emptyList()
        }

        val inventory = mutableListOf<AppInventoryEntity>()

        for (app in apps) {
            try {
                val packageInfo = try {
                    pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
                } catch (e: Exception) {
                    try {
                        pm.getPackageInfo(app.packageName, 0)
                    } catch (e2: Exception) {
                        null
                    }
                } ?: continue

                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                
                val signature = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val signingInfo = try {
                            pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                        } catch (e: Exception) {
                            null
                        }
                        val certs = if (signingInfo?.hasMultipleSigners() == true) {
                            signingInfo.apkContentsSigners
                        } else {
                            signingInfo?.signingCertificateHistory
                        }
                        if (!certs.isNullOrEmpty()) {
                            calculateSha256(certs[0].toByteArray())
                        } else "none"
                    } else {
                        @Suppress("DEPRECATION")
                        val sigs = try {
                            pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES).signatures
                        } catch (e: Exception) {
                            null
                        }
                        if (!sigs.isNullOrEmpty()) {
                            calculateSha256(sigs[0].toByteArray())
                        } else "none"
                    }
                } catch (e: Exception) {
                    "none"
                }

                val permissions = packageInfo.requestedPermissions?.joinToString(",") ?: ""
                val appLabel = try {
                    pm.getApplicationLabel(app).toString().takeIf { it.isNotBlank() } ?: app.packageName
                } catch (e: Exception) {
                    app.packageName
                }

                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }

                inventory.add(
                    AppInventoryEntity(
                        packageName = app.packageName,
                        appName = appLabel,
                        versionName = packageInfo.versionName ?: "unknown",
                        versionCode = versionCode,
                        isSystemApp = isSystem,
                        installTime = packageInfo.firstInstallTime,
                        lastUpdateTime = packageInfo.lastUpdateTime,
                        signatureHash = signature,
                        permissions = permissions,
                        classification = if (isSystem) "SYSTEM" else "USER"
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning package ${app.packageName}", e)
            }
        }
        Log.i(TAG, "Scanned total ${inventory.size} applications.")
        return inventory
    }

    private fun calculateSha256(data: ByteArray): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(data)
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "none"
        }
    }
}
