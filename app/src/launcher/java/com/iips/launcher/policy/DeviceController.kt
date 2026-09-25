package com.iips.launcher.policy

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import com.iips.launcher.policy.DeviceAdminReceiver
import com.iips.launcher.storage.SecurePreferences

object DeviceController {

    private fun isKioskSecurityReady(context: Context): Boolean {
        val ready = SecurePreferences.isReadyForKioskSecurity(context)
        if (!ready) {
            android.util.Log.i(
                "DeviceController",
                "Kiosk/security skipped: DeviceOwner=${DeviceAdminReceiver.isDeviceOwner(context)} " +
                    "EnrollmentComplete=${SecurePreferences.isEnrollmentComplete(context)} " +
                    "Registered=${SecurePreferences.isRegistered(context)} " +
                    "PolicyAvailable=${SecurePreferences.hasValidPolicySnapshot(context)} " +
                    "state=${SecurePreferences.getDeviceState(context)}"
            )
        }
        return ready
    }

    fun enableLockTaskMode(context: Context) {
        // Always wrap in try-catch to prevent any crashes
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return // Not supported on this Android version
            }
            
            // Early return if not Device Owner
            if (!DeviceAdminReceiver.isDeviceOwner(context)) {
                return // Not Device Owner, skip silently
            }
            if (!isKioskSecurityReady(context)) {
                return
            }
            
            try {
                val devicePolicyManager =
                    context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                        ?: return // Couldn't get service
                
                val componentName = DeviceAdminReceiver.getComponentName(context)
                
                // Verify component is active admin BEFORE calling any admin methods
                // This check itself might throw SecurityException, so wrap it
                val isAdminActive = try {
                    devicePolicyManager.isAdminActive(componentName)
                } catch (e: SecurityException) {
                    return // Admin not active, skip
                } catch (e: Exception) {
                    return // Other error, skip
                }
                
                if (!isAdminActive) {
                    return // Admin not active, skip
                }
                
                // Set allowed lock task packages (only this launcher app)
                // This is the call that throws SecurityException if admin is not properly active
                try {
                    devicePolicyManager.setLockTaskPackages(
                        componentName,
                        arrayOf(context.packageName)
                    )
                } catch (e: SecurityException) {
                    // Admin not active or no permission - silently fail
                    android.util.Log.w("DeviceController", "Cannot set lock task packages: ${e.message}")
                } catch (e: Exception) {
                    // Other exceptions - log but don't crash
                    android.util.Log.w("DeviceController", "Error setting lock task packages: ${e.message}")
                }
            } catch (e: SecurityException) {
                // Admin not active or no permission - silently fail
                android.util.Log.w("DeviceController", "Cannot enable lock task mode: ${e.message}")
            } catch (e: Exception) {
                // Other exceptions - log but don't crash
                android.util.Log.w("DeviceController", "Error enabling lock task mode: ${e.message}")
            }
        } catch (e: Exception) {
            // Final catch-all to prevent any crashes
            android.util.Log.w("DeviceController", "Unexpected error in enableLockTaskMode: ${e.message}")
        }
    }

    fun disableLockTaskMode(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (DeviceAdminReceiver.isDeviceOwner(context)) {
                val devicePolicyManager =
                    context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val componentName = DeviceAdminReceiver.getComponentName(context)
                
                // Clear lock task packages
                devicePolicyManager.setLockTaskPackages(componentName, arrayOf())
            }
        }
    }

    fun startLockTask(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                if (!DeviceAdminReceiver.isDeviceOwner(activity)) {
                    return
                }
                if (!isKioskSecurityReady(activity)) {
                    return
                }
                
                // Check if admin is active before starting lock task
                val devicePolicyManager =
                    activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val componentName = DeviceAdminReceiver.getComponentName(activity)
                
                if (!devicePolicyManager.isAdminActive(componentName)) {
                    return
                }
                
                activity.startLockTask()
            } catch (e: SecurityException) {
                // No permission or admin not active - silently fail
                android.util.Log.w("DeviceController", "Cannot start lock task: ${e.message}")
            } catch (e: Exception) {
                // Ignore if already in lock task or other errors
                android.util.Log.w("DeviceController", "Error starting lock task", e)
            }
        }
    }

    fun stopLockTask(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activity.stopLockTask()
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun setUserRestriction(
        context: Context,
        restriction: String,
        disallow: Boolean
    ) {
        try {
            val devicePolicyManager =
                context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

            if (DeviceAdminReceiver.isDeviceOwner(context)) {
                val componentName = DeviceAdminReceiver.getComponentName(context)
                
                // Verify component is active admin
                if (!devicePolicyManager.isAdminActive(componentName)) {
                    return
                }
                
                if (disallow) {
                    devicePolicyManager.addUserRestriction(
                        componentName,
                        restriction
                    )
                } else {
                    devicePolicyManager.clearUserRestriction(
                        componentName,
                        restriction
                    )
                }
            }
        } catch (e: SecurityException) {
            // Admin not active yet
        } catch (e: Exception) {
            // Other exceptions
        }
    }

    fun enableImmersiveMode(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                activity.window.setDecorFitsSystemWindows(false)
                val controller = activity.window.insetsController
                controller?.hide(
                    android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
                )
                controller?.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } catch (e: Exception) {
                android.util.Log.w("DeviceController", "Could not apply WindowInsetsController: ${e.message}")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val flags = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
            activity.window.decorView.systemUiVisibility = flags
            activity.window.decorView.setOnSystemUiVisibilityChangeListener(null)
        }
    }

    fun disableImmersiveMode(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                activity.window.setDecorFitsSystemWindows(true)
                val controller = activity.window.insetsController
                controller?.show(
                    android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
                )
            } catch (e: Exception) {
                android.util.Log.w("DeviceController", "Could not show insets: ${e.message}")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            activity.window.decorView.setOnSystemUiVisibilityChangeListener(null)
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    fun setDefaultLauncher(context: Context) {
        if (!DeviceAdminReceiver.isDeviceOwner(context)) return

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = DeviceAdminReceiver.getComponentName(context)

        // Clear persistent preferred activities for common OEM launchers
        try {
            val commonLaunchers = listOf(
                "com.sec.android.app.launcher",
                "com.google.android.apps.nexuslauncher",
                "com.android.launcher3",
                "com.android.launcher"
            )
            for (pkg in commonLaunchers) {
                dpm.clearPackagePersistentPreferredActivities(admin, pkg)
            }
        } catch (e: Exception) {
            android.util.Log.w("DeviceController", "Could not clear persistent preferred for other launchers: ${e.message}")
        }

        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }

        val activityName = ComponentName(context.packageName, "com.iips.launcher.ui.LauncherActivity")
        
        try {
            dpm.addPersistentPreferredActivity(admin, filter, activityName)
            android.util.Log.i("DeviceController", "Default launcher persistently set to ${activityName.flattenToShortString()}")
        } catch (e: Exception) {
            android.util.Log.e("DeviceController", "Failed to set default launcher: ${e.message}")
        }
    }

    fun isDefaultLauncher(context: Context): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            val currentPkg = resolveInfo?.activityInfo?.packageName
            currentPkg == context.packageName
        } catch (e: Exception) {
            false
        }
    }

    fun ensureDefaultLauncher(context: Context) {
        if (DeviceAdminReceiver.isDeviceOwner(context) && !isDefaultLauncher(context)) {
            android.util.Log.w("DeviceController", "Dotroid is NOT current default launcher! Setting persistent preferred...")
            setDefaultLauncher(context)
        }
    }

    fun clearPersistentPreferredActivities(context: Context, packageName: String) {
        val devicePolicyManager =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (DeviceAdminReceiver.isDeviceOwner(context)) {
                devicePolicyManager.clearPackagePersistentPreferredActivities(
                    DeviceAdminReceiver.getComponentName(context),
                    packageName
                )
            }
        }
    }

    /**
     * Enable factory reset protection - prevents factory reset from Settings UI
     * Note: Hardware recovery mode (button combinations) cannot be fully prevented,
     * but this blocks all software-based factory reset attempts.
     */
    /**
     * Unhides critical system applications that may have been mistakenly hidden.
     * Core packages like com.samsung.android.settings and com.android.settings MUST NEVER be hidden,
     * as doing so breaks SecSettingsProvider and causes boot deadlocks / failure to load up on restart.
     */
    fun unhideCriticalSystemPackages(context: Context) {
        if (!DeviceAdminReceiver.isDeviceOwner(context)) return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        val admin = DeviceAdminReceiver.getComponentName(context)
        if (!dpm.isAdminActive(admin)) return

        val criticalPackages = listOf(
            "com.android.settings",
            "com.android.settings.fallback",
            "com.samsung.android.settings",
            "com.miui.securitycenter",
            "com.miui.security",
            "com.coloros.settings",
            "com.oneplus.settings",
            "com.huawei.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller"
        )
        for (pkg in criticalPackages) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dpm.setApplicationHidden(admin, pkg, false)
                }
            } catch (e: Exception) {
                // Ignore if package does not exist
            }
        }
    }

    /**
     * Enable factory reset protection - prevents factory reset from Settings UI
     * Note: Hardware recovery mode (button combinations) cannot be fully prevented,
     * but this blocks all software-based factory reset attempts.
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
    fun enableFactoryResetProtection(context: Context) {
        try {
            if (!DeviceAdminReceiver.isDeviceOwner(context)) {
                return
            }
            if (!isKioskSecurityReady(context)) {
                return
            }

            val devicePolicyManager =
                context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = DeviceAdminReceiver.getComponentName(context)
            
            // Verify component is active admin
            if (!devicePolicyManager.isAdminActive(componentName)) {
                return
            }

            // Prevent factory reset from Settings
            setUserRestriction(context, android.os.UserManager.DISALLOW_FACTORY_RESET, true)

            // Block access to System Settings (prevents accessing reset options)
            setUserRestriction(context, android.os.UserManager.DISALLOW_FUN, true)

            // Block access to Recovery Mode settings
            try {
                setUserRestriction(context, android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS, true)
            } catch (e: Exception) {
                // May not be available on all Android versions
            }
        } catch (e: SecurityException) {
            // Admin not active or no permission - silently fail
            android.util.Log.w("DeviceController", "Cannot enable factory reset protection: ${e.message}")
        } catch (e: Exception) {
            // Other exceptions - log but don't crash
            android.util.Log.w("DeviceController", "Error enabling factory reset protection", e)
        }
    }

    /**
     * Disable factory reset protection (admin only)
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
    fun disableFactoryResetProtection(context: Context) {
        if (!DeviceAdminReceiver.isDeviceOwner(context)) {
            return
        }

        setUserRestriction(context, android.os.UserManager.DISALLOW_FACTORY_RESET, false)
        setUserRestriction(context, android.os.UserManager.DISALLOW_FUN, false)
    }

    /**
     * Hide an application from the launcher and app list
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun hideApplication(context: Context, packageName: String) {
        val devicePolicyManager =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        if (DeviceAdminReceiver.isDeviceOwner(context)) {
            try {
                devicePolicyManager.setApplicationHidden(
                    DeviceAdminReceiver.getComponentName(context),
                    packageName,
                    true
                )
            } catch (e: Exception) {
                // Package may not exist or permission denied
            }
        }
    }

    /**
     * Unhide an application
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun unhideApplication(context: Context, packageName: String) {
        val devicePolicyManager =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        if (DeviceAdminReceiver.isDeviceOwner(context)) {
            try {
                devicePolicyManager.setApplicationHidden(
                    DeviceAdminReceiver.getComponentName(context),
                    packageName,
                    false
                )
            } catch (e: Exception) {
                // Package may not exist
            }
        }
    }

    /**
     * Block Settings app completely using user restrictions.
     * Note: Never hide com.android.settings or com.samsung.android.settings using setApplicationHidden,
     * as doing so breaks critical system providers (SecSettingsProvider) and causes boot deadlocks on reboot.
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun blockSettingsAccess(context: Context, block: Boolean) {
        if (!DeviceAdminReceiver.isDeviceOwner(context)) {
            return
        }

        // Ensure critical system settings packages remain unhidden
        unhideCriticalSystemPackages(context)

        // Enforce configuration restrictions
        val restrictions = listOf(
            android.os.UserManager.DISALLOW_FACTORY_RESET,
            android.os.UserManager.DISALLOW_CONFIG_DATE_TIME,
            android.os.UserManager.DISALLOW_CONFIG_TETHERING,
            android.os.UserManager.DISALLOW_MODIFY_ACCOUNTS,
            android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS
        )
        restrictions.forEach { restriction ->
            setUserRestriction(context, restriction, block)
        }
    }

    /**
     * Check if factory reset protection is enabled
     */
    fun isFactoryResetProtectionEnabled(context: Context): Boolean {
        val userManager = context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
        return try {
            userManager.hasUserRestriction(android.os.UserManager.DISALLOW_FACTORY_RESET)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Enable comprehensive security - blocks Settings access, prevents uninstallation and force stop
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun enableComprehensiveSecurity(context: Context) {
        try {
            if (!DeviceAdminReceiver.isDeviceOwner(context)) {
                return
            }
            if (!isKioskSecurityReady(context)) {
                return
            }

            val devicePolicyManager =
                context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = DeviceAdminReceiver.getComponentName(context)

            // Verify component is active admin before proceeding
            if (!devicePolicyManager.isAdminActive(componentName)) {
                return
            }

            // Programmatically grant all requested permissions
            grantOwnPermissions(context)

            // Ensure critical system packages are NOT hidden to prevent reboot brick/hang
            unhideCriticalSystemPackages(context)

            // Block Settings access via safe user restrictions
            blockSettingsAccess(context, true)

            // Block access to app info/uninstall/force stop screens
            val restrictions = mutableListOf(
                android.os.UserManager.DISALLOW_CONFIG_CREDENTIALS,
                android.os.UserManager.DISALLOW_MODIFY_ACCOUNTS,
                android.os.UserManager.DISALLOW_APPS_CONTROL,  // Prevents app management access - BLOCKS FORCE STOP
                android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS
            )
            
            // Add additional restrictions if available (Android 6.0+)
            // Note: skip DISALLOW_INSTALL_APPS in DEBUG builds so ADB sideloading still works
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !com.iips.launcher.BuildConfig.DEBUG) {
                try {
                    restrictions.add(android.os.UserManager.DISALLOW_INSTALL_APPS)
                } catch (e: Exception) {
                    // May not be available
                }
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    val field = android.os.UserManager::class.java.getField("DISALLOW_UNINSTALL_APPS")
                    restrictions.add(field.get(null) as String)
                } catch (e: Exception) {
                    // Not available on this Android version
                }
            }

            restrictions.forEach { restriction ->
                try {
                    setUserRestriction(context, restriction, true)
                } catch (e: Exception) {
                    // Some restrictions may not be available on all versions
                }
            }
        } catch (e: SecurityException) {
            // Admin not yet active, skip security setup
        } catch (e: Exception) {
            // Other exceptions, skip security setup
        }

        // Lock the status bar (top bar) to prevent drag-down
        setStatusBarLocked(context, true)
    }

    /**
     * Lock or unlock the status bar (top bar).
     * Prevents pulling down notifications and quick settings.
     */
    fun setStatusBarLocked(context: Context, locked: Boolean) {
        try {
            if (!DeviceAdminReceiver.isDeviceOwner(context)) return

            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = DeviceAdminReceiver.getComponentName(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // This blocks notifications, quick settings, and other overlays
                dpm.setStatusBarDisabled(admin, locked)
                android.util.Log.i("DeviceController", "Status bar locked: $locked")
            }
        } catch (e: Exception) {
            android.util.Log.e("DeviceController", "Failed to set status bar lock state: ${e.message}")
        }
    }
    
    /**
     * Prevent force stop by blocking app info screen access natively
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun preventForceStop(context: Context) {
        try {
            if (!DeviceAdminReceiver.isDeviceOwner(context)) {
                return
            }
            if (!isKioskSecurityReady(context)) {
                return
            }
            
            // Ensure package installer packages are not hidden
            unhideCriticalSystemPackages(context)
            
            // DISALLOW_APPS_CONTROL natively blocks force stop and uninstallation
            setUserRestriction(context, android.os.UserManager.DISALLOW_APPS_CONTROL, true)
        } catch (e: SecurityException) {
            // Admin not active or no permission - silently fail
            android.util.Log.w("DeviceController", "Cannot prevent force stop: ${e.message}")
        } catch (e: Exception) {
            // Other exceptions - log but don't crash
            android.util.Log.w("DeviceController", "Error preventing force stop", e)
        }
    }

    /**
     * Programmatically grants the app's own requested runtime permissions.
     */
    fun grantOwnPermissions(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            if (!DeviceAdminReceiver.isDeviceOwner(context)) return

            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = DeviceAdminReceiver.getComponentName(context)
            val packageName = context.packageName

            val permissions = listOf(
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.CAMERA
            )

            permissions.forEach { permission ->
                try {
                    dpm.setPermissionGrantState(
                        admin,
                        packageName,
                        permission,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    )
                    android.util.Log.i("DeviceController", "Successfully granted permission: $permission")
                } catch (e: Exception) {
                    android.util.Log.w("DeviceController", "Failed to grant permission $permission: ${e.message}")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    dpm.setPermissionGrantState(
                        admin,
                        packageName,
                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    )
                } catch (e: Exception) { }
            }
        } catch (e: Exception) {
            android.util.Log.e("DeviceController", "Error granting permissions", e)
        }
    }

    /**
     * Block swipe-down and Settings access by intercepting system UI
     */
    fun blockSystemUI(activity: Activity) {
        if (!isKioskSecurityReady(activity)) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                activity.window.setDecorFitsSystemWindows(false)
                val controller = activity.window.insetsController
                controller?.hide(
                    android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
                )
                controller?.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } catch (e: Exception) {
                android.util.Log.w("DeviceController", "Could not apply WindowInsetsController in blockSystemUI: ${e.message}")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // Full immersive mode to prevent notifications pull-down
            val flags = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
            activity.window.decorView.systemUiVisibility = flags
            // Explicitly clear any listener to prevent infinite runnable loop on touch
            activity.window.decorView.setOnSystemUiVisibilityChangeListener(null)
        }
    }
}

