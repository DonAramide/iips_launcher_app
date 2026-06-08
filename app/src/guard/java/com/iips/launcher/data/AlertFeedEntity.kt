package com.iips.launcher.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alert_feed")
data class AlertFeedEntity(
    @PrimaryKey val alertId: String,
    val deviceId: String,
    val alertType: String,
    val message: String,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val isAcknowledged: Boolean
)
