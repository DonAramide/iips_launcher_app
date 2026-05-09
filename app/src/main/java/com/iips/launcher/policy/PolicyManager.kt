package com.iips.launcher.policy

import android.util.Log
import com.iips.launcher.network.models.DevicePolicySnapshot
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entry point for policy management.
 * Coordinates syncing and enforcement.
 */
@Singleton
class PolicyManager @Inject constructor(
    private val repository: PolicyRepository,
    private val enforcementEngine: PolicyEnforcementEngine
) {
    companion object {
        private const val TAG = "PolicyManager"
    }

    val currentPolicy: StateFlow<DevicePolicySnapshot?> = repository.currentPolicy

    /**
     * Synchronizes policy with the backend and enforces it immediately.
     */
    suspend fun syncAndEnforce() {
        Log.i(TAG, "Triggering policy sync and enforcement")
        val result = repository.fetchLatestPolicy()
        
        result.onSuccess { snapshot ->
            enforcementEngine.enforce(snapshot)
        }.onFailure { e ->
            Log.e(TAG, "Sync failed, enforcing local cache if available", e)
            currentPolicy.value?.let { 
                enforcementEngine.enforce(it)
            }
        }
    }

    /**
     * Enforces the current local policy.
     * Useful for drift correction and boot-time enforcement.
     */
    fun enforceCurrent() {
        currentPolicy.value?.let {
            enforcementEngine.enforce(it)
        } ?: Log.w(TAG, "No local policy available to enforce")
    }
}
