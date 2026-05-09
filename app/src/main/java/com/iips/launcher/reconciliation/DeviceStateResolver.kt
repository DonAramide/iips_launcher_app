package com.iips.launcher.reconciliation

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.iips.launcher.deviceowner.DeviceOwnerManager
import com.iips.launcher.integrity.IntegrityManager
import com.iips.launcher.policy.DeviceAdminReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the actual real-time state of the device from system services.
 */
@Singleton
class DeviceStateResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceOwnerManager: DeviceOwnerManager,
    private val integrityManager: IntegrityManager
) {
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val pm = context.packageManager
    private val admin = DeviceAdminReceiver.getComponentName(context)

    /**
     * Captures the actual state of the device.
     */
    fun resolveActualState(): ActualDeviceState {
        return ActualDeviceState(
            isDeviceOwner = deviceOwnerManager.isDeviceOwner(),
            isAdminActive = dpm.isAdminActive(admin),
            installedPackages = pm.getInstalledPackages(0).map { it.packageName }.toSet(),
            isLockTaskActive = isLockTaskActive(),
            isLauncherDefault = isLauncherDefault(),
            integrityLevel = integrityManager.getIntegrityLevel(),
            isStatusBarDisabled = isStatusBarDisabled(),
            isSafeBootDisabled = isSafeBootDisabled(),
            isFactoryResetDisabled = isFactoryResetDisabled()
        )
    }

    private fun isLockTaskActive(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION")
            am.isInLockTaskMode
        }
    }

    private fun isLauncherDefault(): Boolean {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        intent.addCategory(android.content.Intent.CATEGORY_HOME)
        val resolveInfo = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName == context.packageName
    }

    private fun isStatusBarDisabled(): Boolean {
        // Unfortunately there is no direct getter for status bar state in DPM.
        // We have to assume it's enforced if we set it, or track it locally.
        return false // Placeholder
    }

    private fun isSafeBootDisabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dpm.getUserRestrictions(admin).getBoolean(android.os.UserManager.DISALLOW_SAFE_BOOT)
        } else false
    }

    private fun isFactoryResetDisabled(): Boolean {
        return dpm.getUserRestrictions(admin).getBoolean(android.os.UserManager.DISALLOW_FACTORY_RESET)
    }
}

/**
 * Data class representing the actual state of the device.
 */
data class ActualDeviceState(
    val isDeviceOwner: Boolean,
    val isAdminActive: Boolean,
    val installedPackages: Set<String>,
    val isLockTaskActive: Boolean,
    val isLauncherDefault: Boolean,
    val integrityLevel: IntegrityManager.IntegrityLevel,
    val isStatusBarDisabled: Boolean,
    val isSafeBootDisabled: Boolean,
    val isFactoryResetDisabled: Boolean
)
