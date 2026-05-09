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

    const val STATE_NEW = "NEW"
    const val STATE_ONBOARDING = "ONBOARDING"
    const val STATE_REGISTERED = "REGISTERED"
    const val STATE_PENDING_APPROVAL = "PENDING_APPROVAL"
    const val STATE_ACTIVE = "ACTIVE"
    const val STATE_LOCKED = "LOCKED"

    fun getDeviceState(context: Context): String {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("device_state", STATE_NEW) ?: STATE_NEW
    }

    fun setDeviceState(context: Context, state: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("device_state", state).apply()
    }

    fun getRequestId(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("request_id", null)
    }

    fun setRequestId(context: Context, requestId: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("request_id", requestId).apply()
    }

    fun getBusinessName(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("business_name", null)
    }

    fun setBusinessName(context: Context, name: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("business_name", name).apply()
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
        return prefs.getString("access_token", null)
    }

    fun setDeviceToken(context: Context, token: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("access_token", token).apply()
    }

    fun getTokenExpiresAt(context: Context): Long {
        val prefs = getEncryptedPrefs(context)
        return prefs.getLong("token_expires_at", 0L)
    }

    fun setTokenExpiresAt(context: Context, expiresAt: Long) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putLong("token_expires_at", expiresAt).apply()
    }

    fun getEnrollmentToken(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("enrollment_token", null)
    }

    fun setEnrollmentToken(context: Context, token: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("enrollment_token", token).apply()
    }

    fun getFingerprintHash(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("fingerprint_hash", null)
    }

    fun setFingerprintHash(context: Context, hash: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("fingerprint_hash", hash).apply()
    }

    fun isRegistered(context: Context): Boolean {
        return getDeviceId(context) != null && getDeviceToken(context) != null
    }

    /**
     * Geofencing Preferences
     */



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

    fun setProposedZones(context: Context, zones: List<com.iips.launcher.config.GeofenceRule>) {
        val prefs = getEncryptedPrefs(context)
        val json = com.google.gson.Gson().toJson(zones)
        prefs.edit().putString("proposed_zones", json).apply()
    }

    fun getProposedZones(context: Context): List<com.iips.launcher.config.GeofenceRule> {
        val prefs = getEncryptedPrefs(context)
        val json = prefs.getString("proposed_zones", null) ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<com.iips.launcher.config.GeofenceRule>>() {}.type
            com.google.gson.Gson().fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isCommandExecuted(context: Context, commandId: String): Boolean {
        val prefs = getEncryptedPrefs(context)
        val executedIds = prefs.getStringSet("executed_command_ids", emptySet()) ?: emptySet()
        return executedIds.contains(commandId)
    }

    fun markCommandAsExecuted(context: Context, commandId: String) {
        val prefs = getEncryptedPrefs(context)
        val executedIds = prefs.getStringSet("executed_command_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        executedIds.add(commandId)
        prefs.edit().putStringSet("executed_command_ids", executedIds).apply()
    }

    fun getPendingCommands(context: Context): List<com.iips.launcher.config.MdmCommand> {
        val prefs = getEncryptedPrefs(context)
        val json = prefs.getString("pending_commands", null) ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<com.iips.launcher.config.MdmCommand>>() {}.type
            com.google.gson.Gson().fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setPendingCommands(context: Context, commands: List<com.iips.launcher.config.MdmCommand>) {
        val prefs = getEncryptedPrefs(context)
        val json = com.google.gson.Gson().toJson(commands)
        prefs.edit().putString("pending_commands", json).apply()
    }

    fun getLastCommandId(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("last_command_id", null)
    }

    fun setLastCommandId(context: Context, id: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("last_command_id", id).apply()
    }

    fun getLastCommandStatus(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("last_command_status", null)
    }

    fun setLastCommandStatus(context: Context, status: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("last_command_status", status).apply()
    }

    fun getRecentNonces(context: Context): List<String> {
        val prefs = getEncryptedPrefs(context)
        val json = prefs.getString("recent_nonces", null) ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
            com.google.gson.Gson().fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setRecentNonces(context: Context, nonces: List<String>) {
        val prefs = getEncryptedPrefs(context)
        val json = com.google.gson.Gson().toJson(nonces)
        prefs.edit().putString("recent_nonces", json).apply()
    }

    fun getDevicePolicySnapshot(context: Context): com.iips.launcher.data.DevicePolicySnapshot? {
        val prefs = getEncryptedPrefs(context)
        val json = prefs.getString("device_policy_snapshot", null) ?: return null
        return try {
            com.google.gson.Gson().fromJson(json, com.iips.launcher.data.DevicePolicySnapshot::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun setDevicePolicySnapshot(context: Context, snapshot: com.iips.launcher.data.DevicePolicySnapshot) {
        val prefs = getEncryptedPrefs(context)
        val snapshotJson = com.google.gson.Gson().toJson(snapshot)
        prefs.edit().putString("device_policy_snapshot", snapshotJson).commit() // Synchronous and atomic
    }

    fun getKioskModeEnabled(context: Context): Boolean {
        return getDevicePolicySnapshot(context)?.kioskMode ?: true
    }

    fun isSettingsLocked(context: Context): Boolean {
        return getDevicePolicySnapshot(context)?.settingsLock ?: true
    }

    fun clearAll(context: Context) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().clear().commit()
    }

    fun getSharedJsonParams(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("shared_json_params", null)
    }

    fun setSharedJsonParams(context: Context, json: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("shared_json_params", json).apply()
    }

    // ── Enterprise QR Provisioning ──────────────────────────────────────────

    /** Backend URL injected via PROVISIONING_ADMIN_EXTRAS_BUNDLE (overrides BuildConfig.BASE_URL). */
    fun getProvisioningBackendUrl(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("provisioning_backend_url", null)
    }

    fun setProvisioningBackendUrl(context: Context, url: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("provisioning_backend_url", url).apply()
    }

    /** Optional tenant ID from provisioning extras. */
    fun getTenantId(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("tenant_id", null)
    }

    fun setTenantId(context: Context, tenantId: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("tenant_id", tenantId).apply()
    }

    /** Optional policy group ID from provisioning extras. */
    fun getPolicyGroupId(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("policy_group_id", null)
    }

    fun setPolicyGroupId(context: Context, groupId: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("policy_group_id", groupId).apply()
    }

    /**
     * True once enterprise provisioning bootstrap has successfully completed.
     * Prevents re-enrollment on subsequent boot cycles.
     */
    fun isProvisioningCompleted(context: Context): Boolean {
        val prefs = getEncryptedPrefs(context)
        return prefs.getBoolean("provisioning_completed", false)
    }

    fun setProvisioningCompleted(context: Context, value: Boolean) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putBoolean("provisioning_completed", value).apply()
    }

    /**
     * Wipe one-time provisioning extras (token, tenant ID, policy group ID, backend URL)
     * immediately after successful enrollment. Does NOT clear the persistent device
     * credentials (device_id, access_token, etc.).
     */
    fun clearProvisioningExtras(context: Context) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit()
            .remove("enrollment_token")
            .remove("tenant_id")
            .remove("policy_group_id")
            .remove("provisioning_backend_url")
            .apply()
    }
}

