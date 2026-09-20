package com.iips.launcher.kiosk

import android.content.Context
import android.util.Log
import com.iips.launcher.policy.DeviceAdminReceiver
import com.iips.launcher.policy.DeviceController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ensures the launcher remains the persistent default home activity.
 */
@Singleton
class LauncherPersistenceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "LauncherPersistence"
    }

    /**
     * Returns true if Dotroid is currently resolved as the default home launcher.
     */
    fun isLauncherDefault(): Boolean {
        return DeviceController.isDefaultLauncher(context)
    }

    /**
     * Forcefully sets the launcher as the persistent default home activity.
     */
    fun ensureLauncherIsDefault() {
        if (!DeviceAdminReceiver.isDeviceOwner(context)) return

        try {
            DeviceController.setDefaultLauncher(context)
            Log.i(TAG, "Launcher persistence verified: ${context.packageName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set persistent preferred activity", e)
        }
    }
}
