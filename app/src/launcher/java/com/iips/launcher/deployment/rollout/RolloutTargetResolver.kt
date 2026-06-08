package com.iips.launcher.deployment.rollout

import android.content.Context
import android.os.Build
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves deployment cohorts based on hardware, software, and tenant metadata.
 */
@Singleton
class RolloutTargetResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Resolves the cohorts for the current device.
     */
    fun resolveCohorts(): Set<String> {
        val cohorts = mutableSetOf<String>()

        // 1. Hardware & OS cohorts
        cohorts.add("HW_\${Build.MANUFACTURER.uppercase()}")
        cohorts.add("MODEL_\${Build.MODEL.uppercase()}")
        cohorts.add("SDK_\${Build.VERSION.SDK_INT}")

        // 2. Tenant cohort
        SecurePreferences.getTenantId(context)?.let {
            cohorts.add("TENANT_\$it")
        }

        // 3. Canary / Risk cohorts (e.g. based on device ID hash)
        val deviceId = SecurePreferences.getDeviceId(context) ?: ""
        val hash = deviceId.hashCode().toLong().and(0xFFFFFFFFL)
        val bucket = (hash % 100).toInt()
        
        if (bucket < 1) cohorts.add("COHORT_CANARY_1")
        if (bucket < 5) cohorts.add("COHORT_CANARY_5")
        if (bucket < 10) cohorts.add("COHORT_BETA_10")
        
        cohorts.add("COHORT_PRODUCTION")

        return cohorts
    }

    /**
     * Checks if the device belongs to a specific rollout target.
     */
    fun isTargetMatch(targetId: String): Boolean {
        return resolveCohorts().contains(targetId)
    }
}
