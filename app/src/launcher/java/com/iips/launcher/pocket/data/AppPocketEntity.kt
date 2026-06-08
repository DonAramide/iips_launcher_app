package com.iips.launcher.pocket.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_pocket")
data class AppPocketEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val apkPath: String?,
    val iconCachePath: String?,
    val installDate: Long?,
    val uninstallDate: Long?,
    val source: String,                  // PRE_INSTALLED, DOWNLOADED, REMOTE_DEPLOY, FILE_TRANSFER
    val status: String,                  // PENDING, AWAITING_APPROVAL, APPROVED, INSTALLED, REJECTED, REMOVED
    val appType: String,                 // SYSTEM, MANAGED, USER, QUASAR_DEPLOYED
    val isRequired: Boolean = false,
    val isMissing: Boolean = false,
    val lastUsedTimestamp: Long? = null,
    val storageUsageBytes: Long = 0L,
    val crashCount: Int = 0,
    val healthStatus: String = "HEALTHY",// HEALTHY, DEGRADED, CRITICAL
    val updateAvailableVersion: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)
