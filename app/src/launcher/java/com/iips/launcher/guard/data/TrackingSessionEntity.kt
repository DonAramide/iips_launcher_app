package com.iips.launcher.guard.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracking_sessions")
data class TrackingSessionEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "start_time") val startTime: Long,
    @ColumnInfo(name = "end_time") val endTime: Long?,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true
)
