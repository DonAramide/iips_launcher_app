package com.iips.launcher.config

import com.google.gson.annotations.SerializedName

/**
 * Data model for remote configuration fetched from the server.
 */
data class ConfigResponse(
    @SerializedName("organization_name")
    val organizationName: String? = null,
    
    @SerializedName("admin_password")
    val adminPassword: String? = null,
    
    @SerializedName("allowed_packages")
    val allowedPackages: List<String>? = null,
    
    @SerializedName("lockdown_enabled")
    val lockdownEnabled: Boolean? = null,
    
    @SerializedName("config_version")
    val configVersion: Int? = null,
    
    @SerializedName("custom_message")
    val customMessage: String? = null
)
