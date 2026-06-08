package com.iips.launcher.network.models

data class AppCatalogResponse(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val isRequired: Boolean,
    val appType: String
)

data class AppPocketAuditRequest(
    val id: Long,
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val actionType: String,
    val userId: String?,
    val timestamp: Long
)
