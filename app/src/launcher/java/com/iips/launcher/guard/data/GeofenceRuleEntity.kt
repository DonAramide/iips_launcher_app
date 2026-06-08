package com.iips.launcher.guard.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "geofence_rules")
data class GeofenceRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    @ColumnInfo(name = "radius_m") val radiusM: Double
)
