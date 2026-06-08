package com.iips.launcher.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracked_coordinates")
data class TrackedCoordinateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)
