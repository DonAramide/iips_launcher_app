package com.iips.launcher.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.iips.launcher.R
import com.iips.launcher.network.models.*

object SecurePreferences {
    private const val PREFS_NAME = "launcher_secure_prefs"

    fun getEncryptedPrefs(context: Context): SharedPreferences {
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
        return prefs.getString("admin_password", "") ?: ""
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
        return prefs.getBoolean("factory_reset_protection", true)
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

    fun getDeviceId(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        val id = prefs.getString("device_id", null)
        return if (id.isNullOrBlank()) null else id
    }

    fun setDeviceId(context: Context, deviceId: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("device_id", deviceId).apply()
    }

    fun getConfigUrl(context: Context): String {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("config_url", com.iips.launcher.BuildConfig.BASE_URL) ?: com.iips.launcher.BuildConfig.BASE_URL
    }

    fun setConfigUrl(context: Context, url: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("config_url", url).apply()
    }

    fun getFingerprintHash(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("fingerprint_hash", null)
    }

    fun setFingerprintHash(context: Context, hash: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("fingerprint_hash", hash).apply()
    }

    fun getPolicyGroupId(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("policy_group_id", null)
    }

    fun setPolicyGroupId(context: Context, groupId: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("policy_group_id", groupId).apply()
    }

    fun getDeviceToken(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        val token = prefs.getString("access_token", null)
        return if (token.isNullOrBlank()) null else token
    }

    fun setDeviceToken(context: Context, token: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("access_token", token).apply()
    }

    fun isProvisioningCompleted(context: Context): Boolean {
        val prefs = getEncryptedPrefs(context)
        return prefs.getBoolean("provisioning_completed", false)
    }

    fun setProvisioningCompleted(context: Context, completed: Boolean) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putBoolean("provisioning_completed", completed).apply()
    }

    fun isRegistered(context: Context): Boolean {
        val id = getDeviceId(context)
        val token = getDeviceToken(context)
        return !id.isNullOrBlank() && !token.isNullOrBlank()
    }

    fun isGeofenceLocked(context: Context): Boolean {
        if (com.iips.launcher.BuildConfig.DEBUG) return false
        val prefs = getEncryptedPrefs(context)
        return prefs.getBoolean("geofence_locked", false)
    }

    fun setGeofenceLocked(context: Context, locked: Boolean) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putBoolean("geofence_locked", locked).apply()
    }

    fun isRemoteLocked(context: Context): Boolean {
        val prefs = getEncryptedPrefs(context)
        return prefs.getBoolean("remote_locked", false)
    }

    fun setRemoteLocked(context: Context, locked: Boolean) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putBoolean("remote_locked", locked).apply()
    }

    fun getProposedZones(context: Context): List<GeofenceRule> {
        val prefs = getEncryptedPrefs(context)
        val json = prefs.getString("proposed_zones", null) ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<GeofenceRule>>() {}.type
            com.google.gson.Gson().fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setProposedZones(context: Context, zones: List<GeofenceRule>) {
        val prefs = getEncryptedPrefs(context)
        val json = com.google.gson.Gson().toJson(zones)
        prefs.edit().putString("proposed_zones", json).apply()
    }

    fun getDevicePolicySnapshot(context: Context): DevicePolicySnapshot? {
        val prefs = getEncryptedPrefs(context)
        val json = prefs.getString("device_policy_snapshot", null) ?: return null
        return try {
            com.google.gson.Gson().fromJson(json, DevicePolicySnapshot::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun setDevicePolicySnapshot(context: Context, snapshot: DevicePolicySnapshot) {
        val prefs = getEncryptedPrefs(context)
        val snapshotJson = com.google.gson.Gson().toJson(snapshot)
        prefs.edit().putString("device_policy_snapshot", snapshotJson).apply()
    }

    fun getKioskModeEnabled(context: Context): Boolean {
        val prefs = getEncryptedPrefs(context)
        return prefs.getBoolean("kiosk_mode_enabled", getDevicePolicySnapshot(context)?.kioskMode ?: true)
    }

    fun setKioskModeEnabled(context: Context, enabled: Boolean) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putBoolean("kiosk_mode_enabled", enabled).apply()
    }

    fun isSettingsLocked(context: Context): Boolean {
        return getDevicePolicySnapshot(context)?.settingsLock ?: true
    }

    fun getSharedJsonParams(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("shared_json_params", null)
    }

    fun setSharedJsonParams(context: Context, json: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("shared_json_params", json).apply()
    }

    fun getTenantId(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        val storedId = prefs.getString("tenant_id", null)
        if (storedId.isNullOrEmpty() || storedId == "default") {
            val bizName = getBusinessName(context)
            if (!bizName.isNullOrEmpty()) {
                return bizName
            }
        }
        return storedId ?: "default"
    }

    fun setTenantId(context: Context, tenantId: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("tenant_id", tenantId).apply()
    }

    fun isCommandExecuted(context: Context, commandId: String?): Boolean {
        if (commandId.isNullOrEmpty()) return true
        val prefs = getEncryptedPrefs(context)
        val executedIds = prefs.getStringSet("executed_command_ids", emptySet()) ?: emptySet()
        return executedIds.contains(commandId)
    }

    fun markCommandAsExecuted(context: Context, commandId: String?) {
        if (commandId.isNullOrEmpty()) return
        val prefs = getEncryptedPrefs(context)
        val executedIds = prefs.getStringSet("executed_command_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        executedIds.add(commandId)
        prefs.edit().putStringSet("executed_command_ids", executedIds).apply()
    }

    fun getPendingCommands(context: Context): List<MdmCommand> {
        val prefs = getEncryptedPrefs(context)
        val json = prefs.getString("pending_commands", null) ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<MdmCommand>>() {}.type
            com.google.gson.Gson().fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setPendingCommands(context: Context, commands: List<MdmCommand>) {
        val prefs = getEncryptedPrefs(context)
        val json = com.google.gson.Gson().toJson(commands)
        prefs.edit().putString("pending_commands", json).apply()
    }

    fun setLastCommandId(context: Context, id: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("last_command_id", id).apply()
    }

    fun setLastCommandStatus(context: Context, status: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("last_command_status", status).apply()
    }

    fun getNextTelemetrySeq(context: Context): Long {
        val prefs = getEncryptedPrefs(context)
        val current = prefs.getLong("telemetry_seq", 0L)
        val next = current + 1
        prefs.edit().putLong("telemetry_seq", next).apply()
        return next
    }

    fun getCurrentTelemetrySeq(context: Context): Long {
        val prefs = getEncryptedPrefs(context)
        return prefs.getLong("telemetry_seq", 0L)
    }

    fun isNonceReplayed(context: Context, nonce: String): Boolean {
        val prefs = getEncryptedPrefs(context)
        val nonces = prefs.getStringSet("recent_nonces", emptySet()) ?: emptySet()
        if (nonces.contains(nonce)) return true
        
        val updated = nonces.toMutableSet()
        updated.add(nonce)
        // Keep only last 100 nonces
        if (updated.size > 100) {
            updated.remove(updated.first())
        }
        prefs.edit().putStringSet("recent_nonces", updated).apply()
        return false
    }

    fun clearAll(context: Context) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().clear().commit()
    }

    // ── Enterprise QR Provisioning ──────────────────────────────────────────

    fun setProvisioningBackendUrl(context: Context, url: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("provisioning_backend_url", url).apply()
    }

    fun getProvisioningBackendUrl(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("provisioning_backend_url", null)
    }

    fun setBackendUrl(context: Context, url: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("backend_url", url).apply()
    }

    fun getBackendUrl(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("backend_url", null)
    }

    fun setEnrollmentToken(context: Context, token: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("enrollment_token", token).apply()
    }

    fun getEnrollmentToken(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("enrollment_token", null)
    }

    fun setAgentCode(context: Context, agentCode: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("agent_code", agentCode).apply()
    }

    fun getAgentCode(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("agent_code", null)
    }

    fun clearProvisioningExtras(context: Context) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit()
            .remove("enrollment_token")
            .remove("tenant_id")
            .remove("policy_group_id")
            .remove("provisioning_backend_url")
            .remove("backend_url")
            .remove("agent_code")
            .apply()
    }
    fun getGeofenceMode(context: Context): String {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("geofence_mode", "enrollment") ?: "enrollment"
    }

    fun setGeofenceMode(context: Context, mode: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("geofence_mode", mode).apply()
    }

    fun getCrashCount(context: Context): Int {
        val prefs = getEncryptedPrefs(context)
        return prefs.getInt("crash_count", 0)
    }

    fun setCrashCount(context: Context, count: Int) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putInt("crash_count", count).apply()
    }

    fun getLastCrashTime(context: Context): Long {
        val prefs = getEncryptedPrefs(context)
        return prefs.getLong("last_crash_time", 0L)
    }

    fun setLastCrashTime(context: Context, time: Long) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putLong("last_crash_time", time).apply()
    }

    fun getCommandAttemptCount(context: Context, commandId: String): Int {
        val prefs = getEncryptedPrefs(context)
        return prefs.getInt("cmd_attempt_$commandId", 0)
    }

    fun setCommandAttemptCount(context: Context, commandId: String, count: Int) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putInt("cmd_attempt_$commandId", count).apply()
    }

    fun getLastRebootTime(context: Context): Long {
        val prefs = getEncryptedPrefs(context)
        return prefs.getLong("last_reboot_time", 0L)
    }

    fun setLastRebootTime(context: Context, time: Long) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putLong("last_reboot_time", time).apply()
    }

    fun getOfflineTelemetryQueue(context: Context): List<String> {
        val prefs = getEncryptedPrefs(context)
        val set = prefs.getStringSet("offline_telemetry_queue", emptySet()) ?: emptySet()
        return set.toList()
    }

    fun setOfflineTelemetryQueue(context: Context, queue: List<String>) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putStringSet("offline_telemetry_queue", queue.toSet()).apply()
    }

    fun getWebSocketUrl(context: Context): String {
        val customUrl = getProvisioningBackendUrl(context)
            ?: getBackendUrl(context)
            ?: getConfigUrl(context)
        
        val normalized = try {
            com.iips.launcher.policy.DeviceAdminReceiver.normalizeBackendUrl(customUrl)
        } catch (e: Exception) {
            customUrl
        }
        
        val wsBase = when {
            normalized.startsWith("https://") -> normalized.replace("https://", "wss://")
            normalized.startsWith("http://") -> normalized.replace("http://", "ws://")
            else -> "wss://$normalized"
        }
        
        val cleanBase = if (wsBase.endsWith("/api/v1/")) {
            wsBase
        } else if (wsBase.endsWith("/api/v1")) {
            "$wsBase/"
        } else {
            val trimmed = wsBase.trimEnd('/')
            if (trimmed.endsWith("/api/v1")) {
                "$trimmed/"
            } else {
                "$trimmed/api/v1/"
            }
        }
        
        return "${cleanBase}do-mdm/devices/ws"
    }
}
