package com.iips.launcher.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_usage_logs")
data class AppUsageLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val startTime: Long,
    val endTime: Long? = null,
    val duration: Long = 0
)





