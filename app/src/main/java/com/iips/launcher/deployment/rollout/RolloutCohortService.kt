package com.iips.launcher.deployment.rollout

import android.content.Context
import android.os.Build
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages device cohort assignments for enterprise rollouts.
 */
@Singleton
class RolloutCohortService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val targetResolver: RolloutTargetResolver
) {
    /**
     * Resolves all cohorts this device belongs to.
     */
    fun getActiveCohorts(): Set<String> {
        val cohorts = mutableSetOf<String>()
        
        // Base hardware cohorts
        cohorts.add("HW_\${Build.MANUFACTURER.uppercase()}")
        cohorts.add("MODEL_\${Build.MODEL.uppercase()}")
        cohorts.add("SDK_\${Build.VERSION.SDK_INT}")

        // Tenant and environment
        SecurePreferences.getTenantId(context)?.let { cohorts.add("TENANT_\$it") }
        
        // Custom assignments from preferences
        // In production, these might be pushed from Quasar
        SecurePreferences.getDeviceId(context)?.let { id ->
            if (id.startsWith("CANARY_")) cohorts.add("COHORT_CANARY")
            if (id.startsWith("BETA_")) cohorts.add("COHORT_BETA")
        }

        return cohorts
    }

    /**
     * Checks if the device matches any of the target cohorts in a deployment.
     */
    fun matchesTarget(targetCohorts: List<String>): Boolean {
        if (targetCohorts.isEmpty() || targetCohorts.contains("ALL")) return true
        val active = getActiveCohorts()
        return targetCohorts.any { it in active }
    }
}
