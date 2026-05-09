package com.iips.launcher.apps.security

import com.iips.launcher.apps.inventory.data.AppInventoryEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppRiskAnalyzer @Inject constructor() {
    enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

    /**
     * Analyzes an app and returns a pair of (RiskLevel, Reasons).
     */
    fun analyze(app: AppInventoryEntity): Pair<RiskLevel, List<String>> {
        val reasons = mutableListOf<String>()
        var level = RiskLevel.LOW

        // 1. Accessibility Service Check
        if (app.accessibilityEnabled) {
            level = maxOfLevel(level, RiskLevel.MEDIUM)
            reasons.add("Accessibility Service enabled")
        }

        // 2. Dangerous Permission Combinations
        if (app.permissions.contains("SYSTEM_ALERT_WINDOW") && app.accessibilityEnabled) {
            level = maxOfLevel(level, RiskLevel.HIGH)
            reasons.add("Accessibility + Overlay combo (High risk for overlay attacks)")
        }

        // 3. Suspicious System App Spoofing
        if (app.packageName.contains("android") && !app.isSystemApp) {
            level = maxOfLevel(level, RiskLevel.CRITICAL)
            reasons.add("Suspicious package name (Potential system spoofing)")
        }

        // 4. Device Admin (Mocked for now as we don't scan it yet)
        if (app.permissions.contains("BIND_DEVICE_ADMIN") && !app.isSystemApp) {
            level = maxOfLevel(level, RiskLevel.HIGH)
            reasons.add("Third-party Device Administrator")
        }

        return Pair(level, reasons)
    }

    private fun maxOfLevel(a: RiskLevel, b: RiskLevel): RiskLevel {
        return if (a.ordinal >= b.ordinal) a else b
    }
}
