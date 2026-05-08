package com.iips.launcher.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(tableName = "app_policies")
data class AppPolicy(
    @PrimaryKey
    @SerializedName("package_name") val packageName: String,
    @SerializedName("mode") val mode: String, // REQUIRED, ALLOWED, BLOCKED
    @SerializedName("version_code") val versionCode: Long? = null,
    @SerializedName("apk_url") val apkUrl: String? = null,
    @SerializedName("checksum") val checksum: String? = null,
    @SerializedName("pinned") val pinned: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)
