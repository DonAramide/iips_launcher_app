package com.iips.launcher.guard.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "location_reports")
data class LocationReportEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "latitude") val latitude: Double,
    @ColumnInfo(name = "longitude") val longitude: Double,
    @ColumnInfo(name = "accuracy") val accuracy: Float,
    @ColumnInfo(name = "speed") val speed: Float,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "is_mock") val isMock: Boolean = false,
    @ColumnInfo(name = "is_synced") val isSynced: Boolean = false
)
