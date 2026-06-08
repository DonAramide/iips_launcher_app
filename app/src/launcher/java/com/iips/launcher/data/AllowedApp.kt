package com.iips.launcher.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "allowed_apps")
data class AllowedApp(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val addedTimestamp: Long = System.currentTimeMillis()
)





