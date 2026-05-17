package com.iips.launcher

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.iips.launcher.data.AppDatabase
import com.iips.launcher.policy.DeviceAdminReceiver
import com.iips.launcher.ui.LauncherActivity
import com.iips.launcher.storage.SecurePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@dagger.hilt.android.HiltAndroidApp
class LauncherApplication : Application(), androidx.work.Configuration.Provider {
    
    @javax.inject.Inject
    lateinit var workerFactory: androidx.hilt.work.HiltWorkerFactory

    @javax.inject.Inject
    lateinit var ownershipWatchdog: com.iips.launcher.deviceowner.OwnershipWatchdog

    @javax.inject.Inject
    lateinit var launcherWatchdog: com.iips.launcher.watchdog.LauncherWatchdog

    @Inject lateinit var rolloutManager: com.iips.launcher.deployment.RolloutManager
    @Inject lateinit var provisioningRecoveryManager: com.iips.launcher.deviceowner.ProvisioningRecoveryManager
    @Inject lateinit var connectionManager: com.iips.launcher.convergence.DotroidRuntimeConnectionManager
    @Inject lateinit var telemetryEngine: com.iips.launcher.convergence.DeviceTelemetryEngine
    @Inject lateinit var complianceRuntime: com.iips.launcher.convergence.ComplianceGovernanceRuntime
    @Inject lateinit var integrityRuntime: com.iips.launcher.convergence.IntegrityTrustRuntime
    @Inject lateinit var replayRuntime: com.iips.launcher.convergence.ReplayRecoveryRuntime
    @Inject lateinit var validationSuite: com.iips.launcher.convergence.DotroidRuntimeValidationSuite

    override fun getWorkManagerConfiguration(): androidx.work.Configuration {
        return androidx.work.Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    }
    
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
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize database and load allowed apps
        database = AppDatabase.getDatabase(this)
        
        // Concurrently spin up runtime enterprise network socket convergence infrastructure
        connectionManager.startConnection()
        telemetryEngine.startHarvesting()
        complianceRuntime.startMonitoring()
        integrityRuntime.startEngine()
        replayRuntime.attemptJournalDrain()
        
        // Execute validation harness checks to verify resilience parameters natively
        validationSuite.runValidationSequence()

        // Load allowed apps with a small delay to ensure database is ready
        Handler(Looper.getMainLooper()).postDelayed({
            loadAllowedApps()
            
            // Start ownership monitoring and recovery
            ownershipWatchdog.start()
            launcherWatchdog.start()
            com.iips.launcher.workers.OwnershipVerificationWorker.schedule(this)
            com.iips.launcher.selfheal.SelfHealingWorker.schedule(this)
            rolloutManager.resumeDeploymentValidation()
            provisioningRecoveryManager.attemptRecovery()
        }, 500)
        
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
                
                val packageName = activity.packageName?.lowercase() ?: ""
                
                // ALWAYS block Settings FIRST
                val isSettingsApp = packageName.contains("settings", ignoreCase = true) ||
                                   packageName == "com.android.settings"
                
                if (isSettingsApp) {
                    handler.post {
                        activity.finish()
                        val intent = Intent(applicationContext, LauncherActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(intent)
                    }
                    return
                }
                
                // Check if allowed
                val isAllowed = synchronized(allowedPackagesLock) {
                    packageName in allowedPackages
                }
                
                if (!isAllowed) {
                    handler.post {
                        activity.finish()
                        val intent = Intent(applicationContext, LauncherActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(intent)
                    }
                }
            }
            
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
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
                val allowedApps = database.appPolicyDao().getAllPolicies()
                val remoteAllowedApps = SecurePreferences.getAllowedApps(applicationContext)
                
                val count: Int
                val packageList: MutableList<String>
                synchronized(allowedPackagesLock) {
                    allowedPackages.clear()
                    packageList = allowedApps
                        .filter { it.mode.uppercase() == "REQUIRED" || it.mode.uppercase() == "ALLOWED" }
                        .map { it.packageName.lowercase() }.toMutableList()
                    packageList.addAll(remoteAllowedApps.map { it.lowercase() })
                    allowedPackages.addAll(packageList)
                    count = allowedPackages.size
                }
                android.util.Log.d("LauncherApplication", "Loaded $count allowed apps: $packageList")
            } catch (e: Exception) {
                android.util.Log.e("LauncherApplication", "Error loading allowed apps: ${e.message}", e)
            }
        }
    }
    
    fun refreshAllowedApps() {
        loadAllowedApps()
    }
    
    fun isPackageAllowed(packageName: String): Boolean {
        return synchronized(allowedPackagesLock) {
            packageName.lowercase() in allowedPackages
        }
    }
}
