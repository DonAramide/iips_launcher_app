package com.iips.launcher.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.iips.launcher.R

object SecurePreferences {
    private const val PREFS_NAME = "launcher_secure_prefs"

    private fun getEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getAdminPassword(context: Context): String {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("admin_password", context.getString(R.string.default_admin_password)) ?: 
               context.getString(R.string.default_admin_password)
    }

    fun setAdminPassword(context: Context, password: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("admin_password", password).apply()
    }

    fun isLockdownEnabled(context: Context): Boolean {
        val prefs = getEncryptedPrefs(context)
        return prefs.getBoolean("lockdown_enabled", true)
    }

    fun setLockdownEnabled(context: Context, enabled: Boolean) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putBoolean("lockdown_enabled", enabled).apply()
    }

    fun isImmersiveModeEnabled(context: Context): Boolean {
        val prefs = getEncryptedPrefs(context)
        return prefs.getBoolean("immersive_mode", true)
    }

    fun setImmersiveModeEnabled(context: Context, enabled: Boolean) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putBoolean("immersive_mode", enabled).apply()
    }

    fun isFactoryResetProtectionEnabled(context: Context): Boolean {
        val prefs = getEncryptedPrefs(context)
        return prefs.getBoolean("factory_reset_protection", true) // Enabled by default
    }

    fun setFactoryResetProtectionEnabled(context: Context, enabled: Boolean) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putBoolean("factory_reset_protection", enabled).apply()
    }

    fun getAllowedApps(context: Context): Set<String> {
        val prefs = getEncryptedPrefs(context)
        return prefs.getStringSet("allowed_apps", emptySet()) ?: emptySet()
    }

    fun setAllowedApps(context: Context, apps: Set<String>) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putStringSet("allowed_apps", apps).apply()
    }

    fun getOrganizationName(context: Context): String {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("organization_name", "www.iips.app") ?: "www.iips.app"
    }

    fun setOrganizationName(context: Context, name: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("organization_name", name).apply()
    }

    fun getConfigUrl(context: Context): String {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("config_url", "https://your-server.com/config.json") ?: "https://your-server.com/config.json"
    }

    fun setConfigUrl(context: Context, url: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("config_url", url).apply()
    }

    fun getDeviceId(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("device_id", null)
    }

    fun setDeviceId(context: Context, deviceId: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("device_id", deviceId).apply()
    }

    fun getDeviceToken(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("device_token", null)
    }

    fun setDeviceToken(context: Context, token: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("device_token", token).apply()
    }

    fun isRegistered(context: Context): Boolean {
        return getDeviceId(context) != null && getDeviceToken(context) != null
    }

    /**
     * Geofencing Preferences
     */

    fun setGeofenceConfig(context: Context, config: com.iips.launcher.config.GeofenceConfigResponse) {
        val prefs = getEncryptedPrefs(context)
        val json = com.google.gson.Gson().toJson(config)
        prefs.edit().putString("geofence_config", json).apply()
    }

    fun getGeofenceConfig(context: Context): com.iips.launcher.config.GeofenceConfigResponse? {
        val prefs = getEncryptedPrefs(context)
        val json = prefs.getString("geofence_config", null) ?: return null
        return try {
            com.google.gson.Gson().fromJson(json, com.iips.launcher.config.GeofenceConfigResponse::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun isGeofenceLocked(context: Context): Boolean {
        val prefs = getEncryptedPrefs(context)
        return prefs.getBoolean("geofence_locked", false)
    }

    fun setGeofenceLocked(context: Context, locked: Boolean) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putBoolean("geofence_locked", locked).apply()
    }

    fun getGeofenceMode(context: Context): String {
        val prefs = getEncryptedPrefs(context)
        // Default to enrollment until we have approved zones
        return prefs.getString("geofence_mode", "enrollment") ?: "enrollment"
    }

    fun setGeofenceMode(context: Context, mode: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("geofence_mode", mode).apply()
    }

    fun setProposedZones(context: Context, zones: List<com.iips.launcher.config.GeofenceZone>) {
        val prefs = getEncryptedPrefs(context)
        val json = com.google.gson.Gson().toJson(zones)
        prefs.edit().putString("proposed_zones", json).apply()
    }

    fun getProposedZones(context: Context): List<com.iips.launcher.config.GeofenceZone> {
        val prefs = getEncryptedPrefs(context)
        val json = prefs.getString("proposed_zones", null) ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<com.iips.launcher.config.GeofenceZone>>() {}.type
            com.google.gson.Gson().fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }
}

