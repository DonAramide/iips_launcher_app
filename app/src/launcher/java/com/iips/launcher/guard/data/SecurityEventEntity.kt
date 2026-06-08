package com.iips.launcher.guard.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "security_events")
data class SecurityEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "payload_json") val payloadJson: String?,
    @ColumnInfo(name = "is_synced") val isSynced: Boolean = false
)
