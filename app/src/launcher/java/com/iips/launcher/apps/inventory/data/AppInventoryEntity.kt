package com.iips.launcher.apps.inventory.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_inventory")
data class AppInventoryEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val isSystemApp: Boolean,
    val installTime: Long,
    val lastUpdateTime: Long,
    val signatureHash: String,
    val permissions: String, // Comma separated
    val accessibilityEnabled: Boolean = false,
    val riskScore: Int = 0,
    val classification: String = "UNKNOWN"
)
