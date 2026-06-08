package com.iips.launcher.install

import android.content.Context
import android.util.Log
import com.iips.launcher.core.StructuredLogger
import com.iips.launcher.watchdog.RecoveryCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages installation rollbacks and failure tracking.
 */
@Singleton
class InstallRollbackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val structuredLogger: StructuredLogger,
    private val recoveryCoordinator: RecoveryCoordinator
) {
    companion object {
        private const val TAG = "InstallRollback"
    }

    /**
     * Records an installation failure and determines if a rollback is needed.
     */
    fun handleFailure(packageName: String, errorCode: Int, errorMessage: String?) {
        Log.e(TAG, "Install failed for $packageName: $errorMessage (Code: $errorCode)")
        
        structuredLogger.logIncident(
            TAG,
            "INSTALL_FAILED",
            "Failed to install package: $packageName. Error code: $errorCode",
            fatal = false
        )
        
        // Treat critical system updates dynamically
        val isCriticalSystemApp = packageName == "com.google.android.webview" || packageName == context.packageName
        if (isCriticalSystemApp) {
            Log.e(TAG, "Critical update failed for $packageName. Triggering emergency recovery.")
            recoveryCoordinator.performEmergencyRecovery()
        }
    }

    /**
     * Records a successful installation.
     */
    fun handleSuccess(packageName: String) {
        Log.i(TAG, "Install successful for $packageName")
        // Clear any pending rollback flags for this package
        val prefs = context.getSharedPreferences("install_rollback_state", Context.MODE_PRIVATE)
        prefs.edit().remove("rollback_pending_$packageName").apply()
    }
}
