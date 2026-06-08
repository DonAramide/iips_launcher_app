package com.iips.launcher.pocket.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_pocket_audits")
data class AppPocketAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val actionType: String,              // APK_INTERCEPTED, SUBMITTED_APPROVAL, APPROVED, REJECTED, INSTALL_SUCCESS, REQUIRED_APP_REMOVED
    val userId: String?,                 // ID of the manager authorizing or initiating
    val timestamp: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)
