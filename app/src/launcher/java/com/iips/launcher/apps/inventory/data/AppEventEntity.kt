package com.iips.launcher.apps.inventory.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_events")
data class AppEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val eventType: String, // INSTALLED, REMOVED, UPDATED
    val timestamp: Long = System.currentTimeMillis(),
    val details: String? = null
)
