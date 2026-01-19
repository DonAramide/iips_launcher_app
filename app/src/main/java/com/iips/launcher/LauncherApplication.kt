package com.iips.launcher

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.iips.launcher.data.AppDatabase
import com.iips.launcher.device.DeviceAdminReceiver
import com.iips.launcher.ui.LauncherActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LauncherApplication : Application() {
    
    private val handler = Handler(Looper.getMainLooper())
    private var lastLaunchTime = 0L
    private val MIN_LAUNCH_INTERVAL = 500L // Reduced to 500ms for faster response
    
    // Cache for allowed apps to avoid database queries on every activity start
    // Use synchronized set for thread-safe access
    @Volatile
    private var allowedPackages = mutableSetOf<String>()
    private val allowedPackagesLock = Any()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var database: AppDatabase
    
    // Settings blocking handler - runs globally, not just when launcher is active
    private val settingsCheckHandler = Handler(Looper.getMainLooper())
    private val settingsCheckRunnable = object : Runnable {
        override fun run() {
            checkAndBlockSettingsGlobally()
            settingsCheckHandler.postDelayed(this, 200) // Check every 200ms - very aggressive
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize database and load allowed apps
        database = AppDatabase.getDatabase(this)
        
        // Load allowed apps with a small delay to ensure database is ready
        Handler(Looper.getMainLooper()).postDelayed({
            loadAllowedApps()
        }, 500)
        
        // Start aggressive Settings monitoring globally (runs even when launcher is in background)
        startGlobalSettingsMonitoring()
        
        // Monitor when other activities launch and bring launcher back - AGGRESSIVE MODE
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            
            override fun onActivityStarted(activity: Activity) {
                val packageName = activity.packageName?.lowercase() ?: ""
                val activityName = activity.javaClass.name
                
                android.util.Log.d("LauncherApplication", "onActivityStarted: $activityName, package: $packageName")
                
                // Never intercept AdminActivity, AppSelectionActivity, or own package activities
                val isAdminActivity = activityName.contains("AdminActivity", ignoreCase = true) ||
                                     activityName == "com.iips.launcher.ui.AdminActivity"
                val isAppSelectionActivity = activityName.contains("AppSelectionActivity", ignoreCase = true) ||
                                            activityName == "com.iips.launcher.ui.AppSelectionActivity"
                val isOwnPackage = packageName == "com.iips.launcher" || 
                                  packageName.contains("iips.launcher", ignoreCase = true)
                
                if (isAdminActivity || isAppSelectionActivity || isOwnPackage) {
                    android.util.Log.d("LauncherApplication", "Allowing own package/admin activity to start: $packageName")
                    return // Don't intercept these activities
                }
                
                // Check if it's Settings app - BLOCK IMMEDIATELY (even from allowed apps)
                val isSettingsApp = packageName.contains("settings", ignoreCase = true) ||
                                   packageName == "com.android.settings" ||
                                   packageName.contains("com.samsung.android.settings") ||
                                   packageName.contains("com.miui.securitycenter") ||
                                   packageName.contains("com.huawei.android.settings") ||
                                   packageName.contains("com.coloros.settings") ||
                                   packageName.contains("com.oneplus.settings")
                
                if (isSettingsApp) {
                    android.util.Log.d("LauncherApplication", "🚫 BLOCKING Settings app immediately (even from allowed app): $packageName")
                    handler.post {
                        try {
                            // Force close Settings immediately
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                activity.finish()
                            }
                            
                            // Bring launcher to front immediately
                            val intent = Intent(applicationContext, LauncherActivity::class.java)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                                           Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                           Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                           Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                           Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            startActivity(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("LauncherApplication", "Error blocking Settings: ${e.message}")
                        }
                    }
                    return // Settings blocked, don't continue
                }
                
                // Check if it's an allowed app - if so, don't intercept at all
                val allowedCount: Int
                val isAllowedApp = synchronized(allowedPackagesLock) {
                    allowedCount = allowedPackages.size
                    packageName in allowedPackages
                }
                
                android.util.Log.d("LauncherApplication", "onActivityStarted check - package: $packageName, isAllowed: $isAllowedApp, allowedCount: $allowedCount")
                
                if (isAllowedApp) {
                    android.util.Log.d("LauncherApplication", "✓ Allowing allowed app to start: $packageName")
                    return // Don't intercept allowed apps
                }
                
                android.util.Log.d("LauncherApplication", "✗ Not allowing app (not in allowed list): $packageName")
                // For other apps, we'll intercept on resume instead to give them time to start
                // This prevents blocking apps that are in the process of launching
            }
            
            override fun onActivityResumed(activity: Activity) {
                val packageName = activity.packageName?.lowercase() ?: ""
                val activityName = activity.javaClass.name
                
                android.util.Log.d("LauncherApplication", "onActivityResumed: $activityName, package: $packageName")
                
                // Check first if it's AdminActivity or own package before intercepting
                val isAdminActivity = activityName.contains("AdminActivity", ignoreCase = true) ||
                                     activityName == "com.iips.launcher.ui.AdminActivity"
                val isAppSelectionActivity = activityName.contains("AppSelectionActivity", ignoreCase = true) ||
                                            activityName == "com.iips.launcher.ui.AppSelectionActivity"
                val isOwnPackage = packageName == "com.iips.launcher" || 
                                  packageName.contains("iips.launcher", ignoreCase = true)
                
                // Never intercept AdminActivity, AppSelectionActivity, or own package activities
                if (isAdminActivity || isAppSelectionActivity || isOwnPackage) {
                    android.util.Log.d("LauncherApplication", "Allowing own package/admin activity to resume: $packageName")
                    return // Don't intercept these activities
                }
                
                // Check if it's Settings app - BLOCK IMMEDIATELY (even from allowed apps)
                val isSettingsApp = packageName.contains("settings", ignoreCase = true) ||
                                   packageName == "com.android.settings" ||
                                   packageName.contains("com.samsung.android.settings") ||
                                   packageName.contains("com.miui.securitycenter") ||
                                   packageName.contains("com.huawei.android.settings") ||
                                   packageName.contains("com.coloros.settings") ||
                                   packageName.contains("com.oneplus.settings")
                
                if (isSettingsApp) {
                    android.util.Log.d("LauncherApplication", "🚫 BLOCKING Settings app on resume (even from allowed app): $packageName")
                    handler.post {
                        try {
                            // Force close Settings immediately
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                activity.finish()
                            }
                            
                            // Bring launcher to front immediately
                            val intent = Intent(applicationContext, LauncherActivity::class.java)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                                           Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                           Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                           Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                           Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            startActivity(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("LauncherApplication", "Error blocking Settings: ${e.message}")
                        }
                    }
                    return // Settings blocked, don't continue
                }
                
                // Check if it's an allowed app - if so, don't intercept
                val allowedCount: Int
                val isAllowedApp = synchronized(allowedPackagesLock) {
                    allowedCount = allowedPackages.size
                    packageName in allowedPackages
                }
                
                android.util.Log.d("LauncherApplication", "onActivityResumed check - package: $packageName, isAllowed: $isAllowedApp, allowedCount: $allowedCount")
                
                if (isAllowedApp) {
                    android.util.Log.d("LauncherApplication", "✓ Allowing allowed app to resume: $packageName")
                    return // Don't intercept allowed apps
                }
                
                android.util.Log.d("LauncherApplication", "✗ Will intercept unauthorized app on resume: $packageName")
                // Intercept unauthorized apps on resume with a small delay to allow proper checking
                handler.postDelayed({
                    // Re-check in case app was added to allowed list
                    val stillNotAllowed = synchronized(allowedPackagesLock) {
                        packageName !in allowedPackages
                    }
                    if (stillNotAllowed && !activity.isFinishing && !activity.isDestroyed) {
                        android.util.Log.d("LauncherApplication", "Intercepting unauthorized app: $packageName")
                        interceptUnauthorizedActivity(activity)
                    } else if (!stillNotAllowed) {
                        android.util.Log.d("LauncherApplication", "App was added to allowed list, not intercepting: $packageName")
                    }
                }, 300) // Small delay to allow app to start properly
            }
            
            private fun interceptUnauthorizedActivity(activity: Activity) {
                // Only intercept if Device Owner is set
                if (!DeviceAdminReceiver.isDeviceOwner(applicationContext)) {
                    return
                }
                
                // Allow LauncherActivity - always allow
                if (activity is LauncherActivity) {
                    return
                }
                
                // Get activity and package info first
                val activityName = activity.javaClass.name
                val packageName = activity.packageName?.lowercase() ?: ""
                
                android.util.Log.d("LauncherApplication", "interceptUnauthorizedActivity called for: $activityName, package: $packageName")
                
                // ALWAYS block Settings FIRST, even if launched from an allowed app
                val isSettingsApp = packageName.contains("settings", ignoreCase = true) ||
                                   packageName == "com.android.settings" ||
                                   packageName.contains("com.samsung.android.settings") ||
                                   packageName.contains("com.miui.securitycenter") ||
                                   packageName.contains("com.huawei.android.settings") ||
                                   packageName.contains("com.coloros.settings") ||
                                   packageName.contains("com.oneplus.settings")
                
                if (isSettingsApp) {
                    android.util.Log.d("LauncherApplication", "🚫 BLOCKING Settings app in interceptUnauthorizedActivity (even from allowed app): $packageName")
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastLaunchTime > MIN_LAUNCH_INTERVAL) {
                        lastLaunchTime = currentTime
                        
                        // Immediate action - no delay
                        handler.post {
                            try {
                                // Force close Settings immediately
                                if (!activity.isFinishing && !activity.isDestroyed) {
                                    activity.finish()
                                }
                                
                                // Bring launcher to front immediately
                                val intent = Intent(applicationContext, LauncherActivity::class.java)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                                               Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                               Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                               Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                               Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                startActivity(intent)
                            } catch (e: Exception) {
                                android.util.Log.e("LauncherApplication", "Error blocking Settings: ${e.message}")
                            }
                        }
                    } else {
                        // If within interval, still try to block but with minimal delay
                        handler.postDelayed({
                            try {
                                if (!activity.isFinishing && !activity.isDestroyed) {
                                    activity.finish()
                                    val intent = Intent(applicationContext, LauncherActivity::class.java)
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                                                   Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                                   Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                    startActivity(intent)
                                }
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }, 50) // Very short delay as fallback
                    }
                    return // Settings blocked, don't continue
                }
                
                // Check if it's from our own package - ALLOW ALL activities from our package
                val isOwnPackage = packageName == "com.iips.launcher" || 
                                  packageName.contains("iips.launcher", ignoreCase = true)
                
                if (isOwnPackage) {
                    // ALLOW any activity from our own package (AdminActivity, AppSelectionActivity, etc.)
                    android.util.Log.d("LauncherApplication", "Allowing activity from own package: $activityName")
                    return
                }
                
                // Explicitly check for AdminActivity and AppSelectionActivity by name
                val isAdminActivity = activityName.contains("AdminActivity", ignoreCase = true) ||
                                     activityName == "com.iips.launcher.ui.AdminActivity"
                val isAppSelectionActivity = activityName.contains("AppSelectionActivity", ignoreCase = true) ||
                                            activityName == "com.iips.launcher.ui.AppSelectionActivity"
                
                if (isAdminActivity || isAppSelectionActivity) {
                    // Explicitly allow these activities - don't intercept
                    android.util.Log.d("LauncherApplication", "Allowing activity: $activityName")
                    return
                }
                
                // Check if the package is in the allowed apps list - check cache first
                val allowedCount: Int
                var isAllowedApp = synchronized(allowedPackagesLock) {
                    allowedCount = allowedPackages.size
                    packageName in allowedPackages
                }
                
                android.util.Log.d("LauncherApplication", "interceptUnauthorizedActivity check - package: $packageName, isAllowed: $isAllowedApp, allowedCount: $allowedCount")
                
                // If not in cache, check database directly as fallback
                if (!isAllowedApp && allowedCount == 0) {
                    // Cache might not be loaded yet, check database directly
                    applicationScope.launch(Dispatchers.IO) {
                        try {
                            val allowedApps = database.allowedAppDao().getAll()
                            val isInDb = allowedApps.any { it.packageName.lowercase() == packageName }
                            
                            if (isInDb) {
                                android.util.Log.d("LauncherApplication", "Found $packageName in database, reloading cache")
                                // Reload cache
                                loadAllowedApps()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("LauncherApplication", "Error checking database: ${e.message}", e)
                        }
                    }
                }
                
                if (isAllowedApp) {
                    // Allow this app - it's in the allowed list
                    android.util.Log.d("LauncherApplication", "✓ Allowing activity from allowed app: $packageName")
                    return
                }
                
                // Block unauthorized apps (apps NOT in the allowed list)
                // Note: isAllowedApp was already checked above and activity was allowed if true
                if (packageName != applicationContext.packageName.lowercase() && 
                    packageName != "com.iips.launcher") {
                    android.util.Log.d("LauncherApplication", "Blocking unauthorized app: $packageName")
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastLaunchTime > MIN_LAUNCH_INTERVAL) {
                        lastLaunchTime = currentTime
                        
                        // Immediate action - no delay
                        handler.post {
                            try {
                                // Force close the current activity immediately
                                if (!activity.isFinishing && !activity.isDestroyed) {
                                    activity.finish()
                                }
                                
                                // Bring launcher to front immediately
                                val intent = Intent(applicationContext, LauncherActivity::class.java)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                                               Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                               Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                               Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                               Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                startActivity(intent)
                            } catch (e: Exception) {
                                // Ignore if launch fails
                            }
                        }
                    } else {
                        // If within interval, still try to block but with minimal delay
                        handler.postDelayed({
                            try {
                                if (!activity.isFinishing && !activity.isDestroyed) {
                                    activity.finish()
                                    val intent = Intent(applicationContext, LauncherActivity::class.java)
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                                                   Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                                   Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                    startActivity(intent)
                                }
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }, 50) // Very short delay as fallback
                    }
                }
            }
            
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                // Reload allowed apps when any activity is destroyed (in case apps were added/removed)
                // This ensures the cache stays up to date
                if (activity is LauncherActivity || activity.javaClass.name.contains("AdminActivity", ignoreCase = true)) {
                    loadAllowedApps()
                }
            }
        })
    }
    
    /**
     * Load allowed apps from database into cache for fast lookup
     */
    private fun loadAllowedApps() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                val allowedApps = database.allowedAppDao().getAll()
                val count: Int
                val packageList: List<String>
                synchronized(allowedPackagesLock) {
                    allowedPackages.clear()
                    packageList = allowedApps.map { it.packageName.lowercase() }
                    allowedPackages.addAll(packageList)
                    count = allowedPackages.size
                }
                android.util.Log.d("LauncherApplication", "Loaded $count allowed apps: $packageList")
            } catch (e: Exception) {
                android.util.Log.e("LauncherApplication", "Error loading allowed apps: ${e.message}", e)
            }
        }
    }
    
    /**
     * Manually refresh the allowed apps cache
     * Can be called when apps are added/removed
     */
    fun refreshAllowedApps() {
        loadAllowedApps()
    }
    
    /**
     * Check if a package is in the allowed apps list
     */
    fun isPackageAllowed(packageName: String): Boolean {
        return synchronized(allowedPackagesLock) {
            packageName.lowercase() in allowedPackages
        }
    }
    
    /**
     * Start global Settings monitoring - runs continuously even when launcher is in background
     */
    private fun startGlobalSettingsMonitoring() {
        // Delay start to ensure Device Owner is set
        Handler(Looper.getMainLooper()).postDelayed({
            if (DeviceAdminReceiver.isDeviceOwner(applicationContext)) {
                android.util.Log.d("LauncherApplication", "Starting global Settings monitoring")
                settingsCheckHandler.post(settingsCheckRunnable)
            }
        }, 1000)
    }
    
    /**
     * Check and block Settings globally - runs from Application context
     * This ensures Settings is blocked even when launcher is not active
     */
    private fun checkAndBlockSettingsGlobally() {
        if (!DeviceAdminReceiver.isDeviceOwner(applicationContext)) {
            return
        }
        
        try {
            val activityManager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningTasks: List<android.app.ActivityManager.RunningTaskInfo>? = try {
                activityManager.getRunningTasks(1)
            } catch (e: SecurityException) {
                android.util.Log.w("LauncherApplication", "getRunningTasks() failed - SecurityException: ${e.message}")
                // Try alternative method using getRunningAppProcesses
                try {
                    val processes = activityManager.runningAppProcesses
                    if (processes != null) {
                        processes.forEach { process ->
                            val packageNames = process.pkgList
                            packageNames.forEach { pkg ->
                                if (pkg.lowercase().contains("settings") || 
                                    pkg == "com.android.settings" ||
                                    pkg.contains("com.samsung.android.settings")) {
                                    // Found Settings process, block it
                                    blockSettingsPackage(pkg)
                                }
                            }
                        }
                    }
                } catch (e2: Exception) {
                    android.util.Log.w("LauncherApplication", "getRunningAppProcesses() also failed: ${e2.message}")
                }
                return
            } catch (e: Exception) {
                android.util.Log.w("LauncherApplication", "getRunningTasks() failed: ${e.message}")
                return
            }
            
            if (runningTasks == null || runningTasks.isEmpty()) {
                return
            }
            
            if (runningTasks.isNotEmpty()) {
                val topActivity = runningTasks[0].topActivity
                val packageName = topActivity?.packageName?.lowercase() ?: ""
                val className = topActivity?.className ?: ""
                
                android.util.Log.v("LauncherApplication", "Global check - Top activity: $packageName ($className)")
                
                // Check if it's Settings app
                val isSettingsApp = packageName.contains("settings", ignoreCase = true) ||
                                   packageName == "com.android.settings" ||
                                   packageName.contains("com.samsung.android.settings") ||
                                   packageName.contains("com.miui.securitycenter") ||
                                   packageName.contains("com.huawei.android.settings") ||
                                   packageName.contains("com.coloros.settings") ||
                                   packageName.contains("com.oneplus.settings") ||
                                   className.contains("Settings", ignoreCase = true)
                
                if (isSettingsApp && 
                    !className.contains("LauncherActivity", ignoreCase = true) &&
                    !packageName.contains("iips.launcher", ignoreCase = true)) {
                    android.util.Log.d("LauncherApplication", "🚫 GLOBAL: Detected Settings app: $packageName ($className)")
                    blockSettingsPackage(packageName)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("LauncherApplication", "Error in checkAndBlockSettingsGlobally: ${e.message}")
        }
    }
    
    /**
     * Block Settings package using Device Owner APIs
     */
    private fun blockSettingsPackage(packageName: String) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val devicePolicyManager = applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                val componentName = com.iips.launcher.device.DeviceAdminReceiver.getComponentName(applicationContext)
                
                if (devicePolicyManager.isAdminActive(componentName)) {
                    // Force stop Settings immediately - this should work with Device Owner
                    try {
                        devicePolicyManager.forceStopPackage(componentName, packageName)
                        android.util.Log.d("LauncherApplication", "Force stopped Settings: $packageName")
                    } catch (e: Exception) {
                        android.util.Log.w("LauncherApplication", "Could not force stop Settings: ${e.message}")
                    }
                    
                    // Hide Settings app
                    try {
                        devicePolicyManager.setApplicationHidden(componentName, packageName, true)
                        android.util.Log.d("LauncherApplication", "Hidden Settings app: $packageName")
                    } catch (e: Exception) {
                        android.util.Log.w("LauncherApplication", "Could not hide Settings: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("LauncherApplication", "Error blocking Settings package: ${e.message}")
        }
        
        // Bring launcher to front immediately
        handler.post {
            try {
                val intent = Intent(applicationContext, com.iips.launcher.ui.LauncherActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                               Intent.FLAG_ACTIVITY_CLEAR_TOP or
                               Intent.FLAG_ACTIVITY_CLEAR_TASK or
                               Intent.FLAG_ACTIVITY_SINGLE_TOP or
                               Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                applicationContext.startActivity(intent)
                android.util.Log.d("LauncherApplication", "Brought launcher to front from global check")
            } catch (e: Exception) {
                android.util.Log.e("LauncherApplication", "Error bringing launcher to front globally: ${e.message}")
            }
        }
    }
}

