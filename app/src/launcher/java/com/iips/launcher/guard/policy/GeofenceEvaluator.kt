package com.iips.launcher.guard.policy

import android.location.Location
import com.iips.launcher.guard.data.GeofenceRuleEntity

class GeofenceEvaluator {
    private var consecutiveExits = 0
    private var consecutiveReentries = 0
    private val accuracyThresholdMeters = 50.0f

    fun evaluate(
        location: Location,
        rules: List<GeofenceRuleEntity>,
        currentState: GuardState
    ): GuardState {
        // Ignore inaccurate location fixes to avoid false triggers
        if (location.accuracy > accuracyThresholdMeters) {
            return currentState
        }

        if (rules.isEmpty()) {
            return GuardState.NORMAL
        }

        var isInsideAny = false
        for (rule in rules) {
            val results = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, rule.lat, rule.lng, results)
            val distance = results[0]
            if (distance <= rule.radiusM) {
                isInsideAny = true
                break
            }
        }

        return when (currentState) {
            GuardState.NORMAL -> {
                if (!isInsideAny) {
                    consecutiveExits = 1
                    GuardState.VIOLATION_PENDING
                } else {
                    consecutiveExits = 0
                    GuardState.NORMAL
                }
            }
            GuardState.VIOLATION_PENDING -> {
                if (!isInsideAny) {
                    consecutiveExits++
                    if (consecutiveExits >= 2) {
                        consecutiveExits = 0
                        GuardState.LOCKED
                    } else {
                        GuardState.VIOLATION_PENDING
                    }
                } else {
                    consecutiveExits = 0
                    GuardState.NORMAL
                }
            }
            GuardState.LOCKED -> {
                if (isInsideAny) {
                    consecutiveReentries = 1
                    GuardState.RECOVERY_PENDING
                } else {
                    consecutiveReentries = 0
                    GuardState.LOCKED
                }
            }
            GuardState.RECOVERY_PENDING -> {
                if (isInsideAny) {
                    consecutiveReentries++
                    if (consecutiveReentries >= 2) {
                        consecutiveReentries = 0
                        GuardState.NORMAL
                    } else {
                        GuardState.RECOVERY_PENDING
                    }
                } else {
                    consecutiveReentries = 0
                    GuardState.LOCKED
                }
            }
        }
    }
}
