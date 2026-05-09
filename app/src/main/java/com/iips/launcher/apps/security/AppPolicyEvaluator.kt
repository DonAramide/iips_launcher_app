package com.iips.launcher.apps.security

import android.content.Context
import android.util.Log
import com.iips.launcher.apps.inventory.data.AppInventoryDao
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPolicyEvaluator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appInventoryDao: AppInventoryDao
) {
    private val TAG = "AppPolicyEvaluator"

    /**
     * Evaluates all installed apps against the current MDM policy.
     * Returns a list of non-compliant package names.
     */
    suspend fun evaluateCompliance(): List<String> {
        val inventory = appInventoryDao.getAll()
        val allowedApps = SecurePreferences.getAllowedApps(context).map { it.lowercase() }.toSet()
        
        val nonCompliant = mutableListOf<String>()
        for (app in inventory) {
            val pkg = app.packageName.lowercase()
            
            // 1. Check against Allowlist
            if (pkg !in allowedApps && !app.isSystemApp && pkg != context.packageName) {
                Log.w(TAG, "Non-compliant app detected: \$pkg")
                nonCompliant.add(pkg)
            }
            // 2. Check for Critical Risk level (classification)
            if (app.classification == "CRITICAL") {
                Log.e(TAG, "CRITICAL RISK app detected: \$pkg")
                if (pkg !in nonCompliant) nonCompliant.add(pkg)
            }
        }
        return nonCompliant
    }
}
