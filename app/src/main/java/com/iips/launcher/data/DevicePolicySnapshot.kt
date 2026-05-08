package com.iips.launcher.data

import com.iips.launcher.config.GeofenceRule
import com.iips.launcher.config.InstallQueueItem

/**
 * Local model representing the full state of the MDM policy.
 * This is treated as a full replacement state.
 */
data class DevicePolicySnapshot(
    val version: String,
    val maxPolicyAge: Long,
    val allowedApps: List<String> = emptyList(),
    val blockedApps: List<String> = emptyList(),
    val kioskMode: Boolean = true,
    val settingsLock: Boolean = true,
    val geofenceRules: List<GeofenceRule> = emptyList(),
    val installQueue: List<InstallQueueItem> = emptyList(),
    val lastUpdatedAt: Long = System.currentTimeMillis()
)

