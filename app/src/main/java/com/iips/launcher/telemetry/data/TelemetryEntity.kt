package com.iips.launcher.telemetry.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_telemetry")
data class TelemetryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val payload: String,
    val timestamp: Long = System.currentTimeMillis()
)
