package com.iips.launcher.policy

import android.content.Context
import com.iips.launcher.network.ConfigService
import com.iips.launcher.network.models.DevicePolicySnapshot
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing enterprise policies.
 * Handles local caching and backend synchronization.
 */
@Singleton
class PolicyRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configService: ConfigService
) {
    private val _currentPolicy = MutableStateFlow<DevicePolicySnapshot?>(SecurePreferences.getDevicePolicySnapshot(context))
    val currentPolicy: StateFlow<DevicePolicySnapshot?> = _currentPolicy

    /**
     * Updates the local policy cache and persists it.
     */
    fun updateLocalPolicy(snapshot: DevicePolicySnapshot) {
        SecurePreferences.setDevicePolicySnapshot(context, snapshot)
        if (snapshot.geofenceRules.isNotEmpty()) {
            SecurePreferences.setGeofenceMode(context, "active")
            SecurePreferences.setProposedZones(context, emptyList())
        }
        _currentPolicy.value = snapshot
    }

    /**
     * Fetches the latest policy from the backend.
     */
    suspend fun fetchLatestPolicy(): Result<DevicePolicySnapshot> {
        return try {
            val token = SecurePreferences.getDeviceToken(context)
            val deviceId = SecurePreferences.getDeviceId(context) ?: return Result.failure(Exception("Device not enrolled"))
            
            val response = if (!token.isNullOrEmpty()) {
                val res = configService.fetchPolicy("Bearer $token")
                if (res.isSuccessful) res else configService.getDevicePolicy(deviceId)
            } else {
                configService.getDevicePolicy(deviceId)
            }
            
            if (response.isSuccessful) {
                val envelope = response.body() ?: return Result.failure(Exception("Empty policy response"))
                val policyResponse = envelope.data ?: return Result.failure(Exception("Empty policy data"))
                
                // Map PolicyResponse to DevicePolicySnapshot
                // For now, we create a snapshot from the response. 
                // In a real app, the backend should return the full snapshot structure.
                val snapshot = DevicePolicySnapshot(
                    version = "1.0",
                    maxPolicyAge = 3600000L, // 1 hour
                    lastUpdatedAt = System.currentTimeMillis(),
                    allowedApps = policyResponse.allowed_apps ?: emptyList(),
                    blockedApps = policyResponse.blocked_apps ?: emptyList(),
                    kioskMode = policyResponse.kiosk_mode,
                    settingsLock = policyResponse.settings_lock,
                    geofenceRules = policyResponse.geofence_rules ?: emptyList(),
                    installQueue = policyResponse.install_queue ?: emptyList()
                )
                
                updateLocalPolicy(snapshot)
                Result.success(snapshot)
            } else {
                Result.failure(Exception("Failed to fetch policy: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
