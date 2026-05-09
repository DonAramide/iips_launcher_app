package com.iips.launcher.kiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.iips.launcher.policy.DeviceAdminReceiver
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

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = DeviceAdminReceiver.getComponentName(context)

    /**
     * Forcefully sets the launcher as the persistent default home activity.
     */
    fun ensureLauncherIsDefault() {
        if (!DeviceAdminReceiver.isDeviceOwner(context)) return

        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }

        val activityName = ComponentName(context, "com.iips.launcher.ui.LauncherActivity")
        
        try {
            dpm.addPersistentPreferredActivity(admin, filter, activityName)
            Log.i(TAG, "Launcher persistence verified: \${activityName.flattenToShortString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set persistent preferred activity", e)
        }
    }
}
