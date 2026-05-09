package com.iips.launcher.policy

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import com.iips.launcher.policy.DeviceAdminReceiver

object DeviceController {

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
        }
    }

    fun disableImmersiveMode(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    fun setDefaultLauncher(context: Context) {
        if (!DeviceAdminReceiver.isDeviceOwner(context)) return

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = DeviceAdminReceiver.getComponentName(context)

        val filter = android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
            addCategory(android.content.Intent.CATEGORY_DEFAULT)
        }

        val activityName = ComponentName(context, "com.iips.launcher.ui.LauncherActivity")
        
        try {
            dpm.addPersistentPreferredActivity(admin, filter, activityName)
            android.util.Log.i("DeviceController", "Default launcher set to ${activityName.flattenToShortString()}")
        } catch (e: Exception) {
            android.util.Log.e("DeviceController", "Failed to set default launcher: ${e.message}")
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
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
    fun enableFactoryResetProtection(context: Context) {
        try {
            if (!DeviceAdminReceiver.isDeviceOwner(context)) {
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

            // Prevent users from accessing Developer Options
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    // Hide Settings app from launcher (Android 7.0+)
                    hideApplication(context, "com.android.settings")
                } catch (e: Exception) {
                    // Some devices may have different Settings package name
                }
            }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                unhideApplication(context, "com.android.settings")
            } catch (e: Exception) {
                // Settings app restore
            }
        }
    }

    /**
     * Hide an application from the launcher and app list
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun hideApplication(context: Context, packageName: String) {
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
    private fun unhideApplication(context: Context, packageName: String) {
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
     * Block Settings app completely (alternative method)
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun blockSettingsAccess(context: Context, block: Boolean) {
        if (!DeviceAdminReceiver.isDeviceOwner(context)) {
            return
        }

        val settingsPackages = listOf(
            "com.android.settings",
            "com.android.settings.fallback",  // Some OEMs use this
            "com.samsung.android.settings",    // Samsung
            "com.miui.securitycenter"          // Xiaomi
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            settingsPackages.forEach { packageName ->
                try {
                    hideApplication(context, packageName)
                } catch (e: Exception) {
                    // Ignore if package doesn't exist
                }
            }
        }

        // Additional restrictions
        if (block) {
            setUserRestriction(context, android.os.UserManager.DISALLOW_FACTORY_RESET, true)
            setUserRestriction(context, android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS, true)
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
     * Enable comprehensive security - blocks Settings, prevents uninstallation and force stop
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun enableComprehensiveSecurity(context: Context) {
        try {
            if (!DeviceAdminReceiver.isDeviceOwner(context)) {
                return
            }

            val devicePolicyManager =
                context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = DeviceAdminReceiver.getComponentName(context)

            // Verify component is active admin before proceeding
            if (!devicePolicyManager.isAdminActive(componentName)) {
                // Device Owner should make admin active, but if not, skip security setup
                return
            }

            // Block Settings access completely - do this aggressively
            blockSettingsAccess(context, true)
            
            // Hide Settings app immediately
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val settingsPackages = listOf(
                    "com.android.settings",
                    "com.android.settings.fallback",
                    "com.samsung.android.settings",
                    "com.miui.securitycenter",
                    "com.miui.security",
                    "com.coloros.settings",  // Oppo/OnePlus
                    "com.oneplus.settings",   // OnePlus
                    "com.huawei.android.settings" // Huawei
                )
                
                settingsPackages.forEach { packageName ->
                    try {
                        devicePolicyManager.setApplicationHidden(componentName, packageName, true)
                    } catch (e: Exception) {
                        // Package may not exist or permission denied
                    }
                }
            }

            // Block access to app info/uninstall/force stop screens
            val restrictions = mutableListOf(
                android.os.UserManager.DISALLOW_CONFIG_CREDENTIALS,
                android.os.UserManager.DISALLOW_DEBUGGING_FEATURES,
                android.os.UserManager.DISALLOW_SAFE_BOOT,
                android.os.UserManager.DISALLOW_USB_FILE_TRANSFER,
                android.os.UserManager.DISALLOW_MODIFY_ACCOUNTS,
                android.os.UserManager.DISALLOW_APPS_CONTROL,  // Prevents app management access - BLOCKS FORCE STOP
                android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS
            )
            
            // Add additional restrictions if available (Android 6.0+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    restrictions.add(android.os.UserManager.DISALLOW_INSTALL_APPS)  // Also prevents viewing app details
                } catch (e: Exception) {
                    // May not be available
                }
            }
            
            // DISALLOW_UNINSTALL_APPS may not exist on all versions, so we handle it separately
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    // Use reflection to check if available
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
            
            // Prevent force stop by blocking app info access
            // Device Owner apps cannot be force stopped if DISALLOW_APPS_CONTROL is set
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Hide the app's own entry from app info (prevents force stop)
                // Actually, we can't hide ourselves, but we block access to app info screen
            }
        } catch (e: SecurityException) {
            // Admin not yet active, skip security setup
        } catch (e: Exception) {
            // Other exceptions, skip security setup
        }

        // Note: Swipe-down to notifications is blocked via immersive mode
        // which is handled separately in blockSystemUI()
    }
    
    /**
     * Prevent force stop by blocking app info screen access
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun preventForceStop(context: Context) {
        try {
            if (!DeviceAdminReceiver.isDeviceOwner(context)) {
                return
            }
            
            val devicePolicyManager =
                context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = DeviceAdminReceiver.getComponentName(context)
            
            // Verify component is active admin
            if (!devicePolicyManager.isAdminActive(componentName)) {
                return
            }
            
            // Block access to app info screen which is used for force stop
            try {
                // Hide all package manager apps that show app info
                val pmApps = listOf(
                    "com.android.packageinstaller",
                    "com.google.android.packageinstaller"
                )
                
                pmApps.forEach { packageName ->
                    try {
                        devicePolicyManager.setApplicationHidden(componentName, packageName, true)
                    } catch (e: Exception) {
                        // Package may not exist
                    }
                }
            } catch (e: Exception) {
                // Handle exception
            }
        } catch (e: SecurityException) {
            // Admin not active or no permission - silently fail
            android.util.Log.w("DeviceController", "Cannot prevent force stop: ${e.message}")
        } catch (e: Exception) {
            // Other exceptions - log but don't crash
            android.util.Log.w("DeviceController", "Error preventing force stop", e)
        }
    }

    /**
     * Block swipe-down and Settings access by intercepting system UI
     */
    fun blockSystemUI(activity: Activity) {
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

            // Set listener to re-apply when system UI is shown
            activity.window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                    // System UI is visible, hide it again
                    activity.window.decorView.post {
                        activity.window.decorView.systemUiVisibility = flags
                    }
                }
            }
        }
    }
}

