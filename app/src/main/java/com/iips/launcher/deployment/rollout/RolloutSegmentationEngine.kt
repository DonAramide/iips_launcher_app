package com.iips.launcher.deployment.rollout

import android.content.Context
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Determines device eligibility for progressive rollouts based on percentage thresholds.
 */
@Singleton
class RolloutSegmentationEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Checks if the device falls within the specified rollout percentage.
     * @param percentage The rollout percentage (0-100).
     * @param salt Optional salt to ensure different rollouts use different buckets.
     */
    fun isEligible(percentage: Int, salt: String = ""): Boolean {
        if (percentage >= 100) return true
        if (percentage <= 0) return false

        val deviceId = SecurePreferences.getDeviceId(context) ?: return false
        
        // Deterministic hash based on Device ID and Salt
        val combined = "\$deviceId:\$salt"
        val hash = combined.hashCode().toLong().and(0xFFFFFFFFL)
        val bucket = (hash % 100).toInt()

        return bucket < percentage
    }
}
