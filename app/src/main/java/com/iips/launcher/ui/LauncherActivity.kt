package com.iips.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.iips.launcher.R
import com.iips.launcher.data.AllowedApp
import com.iips.launcher.data.AppDatabase
import com.iips.launcher.data.AppInfo
import com.iips.launcher.databinding.ActivityLauncherBinding
import com.iips.launcher.device.DeviceAdminReceiver
import com.iips.launcher.utils.DeviceController
import com.iips.launcher.utils.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class LauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding
    private lateinit var database: AppDatabase
    private lateinit var appAdapter: AppGridAdapter
    private var allApps: List<AppInfo> = emptyList()
    private val timeHandler = Handler(Looper.getMainLooper())
    private val timeRunnable = object : Runnable {
        override fun run() {
            updateStatusBar()
            timeHandler.postDelayed(this, 1000) // Update every second
        }
    }
    private var batteryReceiver: BroadcastReceiver? = null
    private var settingsReceiver: BroadcastReceiver? = null
    
    // Track when we launch an allowed app to prevent lock task from being re-enabled
    private var lastLaunchedAllowedApp: String? = null
    private var lastLaunchTime: Long = 0
    private val LOCK_TASK_DISABLE_DURATION = 3000L // Keep lock task disabled for 3 seconds after launching app

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)

        setupUI()
        loadApps()
        setupDeviceControls()
        setupStatusBar()
        startStatusBarUpdates()

        // Handle admin access - long press on logo or settings icon
        binding.adminButton.setOnLongClickListener {
            showAdminPasswordDialog()
            true
        }
        
        // Monitor for Settings launches and intercept
        registerSettingsInterceptor()
    }

    override fun onResume() {
        super.onResume()
        
        // Check what activity is currently showing
        val topActivityName = try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningTasks = activityManager.getRunningTasks(1)
            if (runningTasks.isNotEmpty()) {
                runningTasks[0].topActivity?.className ?: ""
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
        
        val isAdminActivityVisible = topActivityName.contains("AdminActivity", ignoreCase = true)
        val isAppSelectionActivityVisible = topActivityName.contains("AppSelectionActivity", ignoreCase = true)
        
        // Check if an allowed app is currently showing
        val isAllowedAppVisible = try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningTasks = activityManager.getRunningTasks(1)
            if (runningTasks.isNotEmpty()) {
                val topActivity = runningTasks[0].topActivity
                val packageName = topActivity?.packageName?.lowercase() ?: ""
                // Check if it's not our package and not settings
                val isNotOwnPackage = !packageName.contains("iips.launcher", ignoreCase = true)
                val isNotSettings = !packageName.contains("settings", ignoreCase = true)
                
                if (isNotOwnPackage && isNotSettings && packageName.isNotEmpty()) {
                    // Check if package is in allowed list using cache from Application
                    val app = application as? com.iips.launcher.LauncherApplication
                    app?.isPackageAllowed(packageName) == true
                } else {
                    false
                }
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.w("LauncherActivity", "Error checking allowed app visibility: ${e.message}")
            false
        }
        
        android.util.Log.d("LauncherActivity", "onResume - Top activity: $topActivityName, isAllowedAppVisible: $isAllowedAppVisible")
        
        // Always reload apps when resuming to get latest selections
        loadApps()
        
        setupDeviceControls()
        
        // Check if we recently launched an allowed app
        val recentlyLaunchedAllowedApp = lastLaunchedAllowedApp != null && 
                                        (System.currentTimeMillis() - lastLaunchTime) < LOCK_TASK_DISABLE_DURATION
        
        // If we recently launched an allowed app and we're coming back, clear the tracking
        if (recentlyLaunchedAllowedApp && isAllowedAppVisible) {
            android.util.Log.d("LauncherActivity", "Allowed app is visible, keeping lock task disabled")
            // Keep tracking for now - will clear when app closes
        } else if (!isAllowedAppVisible && recentlyLaunchedAllowedApp) {
            // If we recently launched but app is not visible, might have closed - clear tracking after a bit
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isAllowedAppVisible) {
                    android.util.Log.d("LauncherActivity", "Clearing launch tracking - app no longer visible")
                    lastLaunchedAllowedApp = null
                }
            }, 1000)
        }
        
        // Always block system UI when resuming (but only if we're in foreground)
        if (!isAllowedAppVisible) {
            DeviceController.blockSystemUI(this)
        }
        
        // Force lock task if Device Owner - BUT don't enable if AdminActivity, AppSelectionActivity, allowed app is visible, OR we recently launched an allowed app
        if (DeviceAdminReceiver.isDeviceOwner(this) && 
            !isAdminActivityVisible && 
            !isAppSelectionActivityVisible && 
            !isAllowedAppVisible &&
            !recentlyLaunchedAllowedApp) {
            DeviceController.startLockTask(this)
            SecurePreferences.setLockdownEnabled(this, true)
        } else if (isAllowedAppVisible || recentlyLaunchedAllowedApp) {
            // Ensure lock task is stopped when allowed app is visible or recently launched
            try {
                DeviceController.stopLockTask(this)
                android.util.Log.d("LauncherActivity", "Stopped lock task because allowed app is visible or recently launched")
            } catch (e: Exception) {
                android.util.Log.w("LauncherActivity", "Could not stop lock task: ${e.message}")
            }
        }
        
        // Update status bar immediately
        updateStatusBar()
    }
    
    override fun onPause() {
        super.onPause()
        
        // Check if we recently launched an allowed app (within last 3 seconds)
        val recentlyLaunchedAllowedApp = lastLaunchedAllowedApp != null && 
                                        (System.currentTimeMillis() - lastLaunchTime) < LOCK_TASK_DISABLE_DURATION
        
        // Check if AdminActivity, AppSelectionActivity, or allowed app is launching
        val isAdminActivityLaunching = try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningTasks = activityManager.getRunningTasks(1)
            if (runningTasks.isNotEmpty()) {
                val topActivity = runningTasks[0].topActivity
                val className = topActivity?.className ?: ""
                className.contains("AdminActivity", ignoreCase = true) ||
                className.contains("AppSelectionActivity", ignoreCase = true)
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
        
        // Check if an allowed app is launching - check multiple running tasks, not just top
        val isAllowedAppLaunching = try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            // Check multiple tasks to catch apps that are launching
            val runningTasks = activityManager.getRunningTasks(5) // Check top 5 tasks
            val app = application as? com.iips.launcher.LauncherApplication
            
            runningTasks.any { task ->
                val topActivity = task.topActivity
                val packageName = topActivity?.packageName?.lowercase() ?: ""
                val isNotOwnPackage = !packageName.contains("iips.launcher", ignoreCase = true)
                val isNotSettings = !packageName.contains("settings", ignoreCase = true)
                
                if (isNotOwnPackage && isNotSettings && packageName.isNotEmpty()) {
                    val isAllowed = app?.isPackageAllowed(packageName) == true
                    if (isAllowed) {
                        android.util.Log.d("LauncherActivity", "Found allowed app in running tasks: $packageName")
                    }
                    isAllowed
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("LauncherActivity", "Error checking allowed app in onPause: ${e.message}")
            false
        }
        
        android.util.Log.d("LauncherActivity", "onPause - isAdminActivityLaunching: $isAdminActivityLaunching, isAllowedAppLaunching: $isAllowedAppLaunching, recentlyLaunchedAllowedApp: $recentlyLaunchedAllowedApp (${lastLaunchedAllowedApp})")
        
        // Don't re-enable lock task if AdminActivity, allowed app is launching, OR we recently launched an allowed app
        if (DeviceAdminReceiver.isDeviceOwner(this) && 
            SecurePreferences.isLockdownEnabled(this) && 
            !isAdminActivityLaunching && 
            !isAllowedAppLaunching &&
            !recentlyLaunchedAllowedApp) {
            // Only re-enable lock task if AdminActivity or allowed app is NOT launching AND we didn't recently launch one
            Handler(Looper.getMainLooper()).postDelayed({
                // Double-check: if we recently launched an allowed app, don't re-enable
                val stillRecentlyLaunched = lastLaunchedAllowedApp != null && 
                                          (System.currentTimeMillis() - lastLaunchTime) < LOCK_TASK_DISABLE_DURATION
                
                // Double-check AdminActivity or allowed app is still not showing - check top 5 tasks
                val stillCanReenableLockTask = try {
                    if (stillRecentlyLaunched) {
                        android.util.Log.d("LauncherActivity", "Still within lock task disable duration, not re-enabling")
                        false
                    } else {
                        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                        val runningTasks = activityManager.getRunningTasks(5) // Check top 5 tasks
                        val app = application as? com.iips.launcher.LauncherApplication
                        
                        val hasAdminOrAllowedApp = runningTasks.any { task ->
                            val topActivity = task.topActivity
                            val className = topActivity?.className ?: ""
                            val packageName = topActivity?.packageName?.lowercase() ?: ""
                            
                            val isAdmin = className.contains("AdminActivity", ignoreCase = true) ||
                                         className.contains("AppSelectionActivity", ignoreCase = true)
                            
                            val isAllowed = if (!packageName.contains("iips.launcher", ignoreCase = true) &&
                                               !packageName.contains("settings", ignoreCase = true) &&
                                               packageName.isNotEmpty()) {
                                app?.isPackageAllowed(packageName) == true
                            } else {
                                false
                            }
                            
                            if (isAdmin || isAllowed) {
                                android.util.Log.d("LauncherActivity", "Double-check found admin/allowed app: $packageName")
                            }
                            
                            isAdmin || isAllowed
                        }
                        
                        !hasAdminOrAllowedApp
                    }
                } catch (e: Exception) {
                    android.util.Log.w("LauncherActivity", "Error in double-check: ${e.message}")
                    true
                }
                
                if (!isFinishing && !isDestroyed && stillCanReenableLockTask) {
                    try {
                        DeviceController.startLockTask(this)
                        android.util.Log.d("LauncherActivity", "Re-enabled lock task in onPause")
                    } catch (e: Exception) {
                        // Ignore if already in lock task
                    }
                } else {
                    android.util.Log.d("LauncherActivity", "Not re-enabling lock task - admin/allowed app still visible or recently launched")
                }
            }, 500) // Increased delay to ensure app has time to start
        } else {
            android.util.Log.d("LauncherActivity", "Not re-enabling lock task in onPause - admin/allowed app launching or recently launched")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        timeHandler.removeCallbacks(timeRunnable)
        batteryReceiver?.let { unregisterReceiver(it) }
        settingsReceiver?.let { unregisterReceiver(it) }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent) // Update intent to prevent loops
        // Settings blocking handled via Device Owner APIs
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Don't enable lock task if AdminActivity is currently showing
            val isAdminActivityVisible = try {
                val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val runningTasks = activityManager.getRunningTasks(1)
                if (runningTasks.isNotEmpty()) {
                    val topActivity = runningTasks[0].topActivity
                    topActivity?.className?.contains("AdminActivity", ignoreCase = true) == true
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
            
            // Always block system UI to prevent swipe-down access
            DeviceController.blockSystemUI(this)
            
            // Enable immersive mode always (prevents swipe-down)
            DeviceController.enableImmersiveMode(this)
            
            // Only enforce lock task if Device Owner AND AdminActivity is NOT visible
            if (DeviceAdminReceiver.isDeviceOwner(this) && !isAdminActivityVisible) {
                DeviceController.startLockTask(this)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        
        // Check if we recently launched an allowed app
        val recentlyLaunchedAllowedApp = lastLaunchedAllowedApp != null && 
                                        (System.currentTimeMillis() - lastLaunchTime) < LOCK_TASK_DISABLE_DURATION
        
        // Check if AdminActivity or allowed app is visible - don't interfere with it
        val isAdminActivityVisible = try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningTasks = activityManager.getRunningTasks(5) // Check top 5 tasks
            runningTasks.any { task ->
                val topActivity = task.topActivity
                val className = topActivity?.className ?: ""
                className.contains("AdminActivity", ignoreCase = true) ||
                className.contains("AppSelectionActivity", ignoreCase = true)
            }
        } catch (e: Exception) {
            false
        }
        
        // Check if an allowed app is visible
        val isAllowedAppVisible = try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningTasks = activityManager.getRunningTasks(5) // Check top 5 tasks
            val app = application as? com.iips.launcher.LauncherApplication
            
            runningTasks.any { task ->
                val topActivity = task.topActivity
                val packageName = topActivity?.packageName?.lowercase() ?: ""
                val isNotOwnPackage = !packageName.contains("iips.launcher", ignoreCase = true)
                val isNotSettings = !packageName.contains("settings", ignoreCase = true)
                
                if (isNotOwnPackage && isNotSettings && packageName.isNotEmpty()) {
                    app?.isPackageAllowed(packageName) == true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
        
        android.util.Log.d("LauncherActivity", "onUserLeaveHint - isAdminActivityVisible: $isAdminActivityVisible, isAllowedAppVisible: $isAllowedAppVisible, recentlyLaunchedAllowedApp: $recentlyLaunchedAllowedApp")
        
        // Don't bring launcher back if AdminActivity, allowed app is visible, OR we recently launched an allowed app
        if (isAdminActivityVisible || isAllowedAppVisible || recentlyLaunchedAllowedApp) {
            android.util.Log.d("LauncherActivity", "Not bringing launcher back in onUserLeaveHint - admin/allowed app visible or recently launched")
            return
        }
        
        // User trying to leave - force back to launcher
        if (DeviceAdminReceiver.isDeviceOwner(this)) {
            Handler(Looper.getMainLooper()).postDelayed({
                // Double-check admin/allowed app is still not showing or recently launched
                val stillNotAdminOrAllowed = try {
                    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val runningTasks = activityManager.getRunningTasks(5) // Check top 5 tasks
                    val app = application as? com.iips.launcher.LauncherApplication
                    
                    val stillHasAdminOrAllowed = runningTasks.any { task ->
                        val topActivity = task.topActivity
                        val className = topActivity?.className ?: ""
                        val packageName = topActivity?.packageName?.lowercase() ?: ""
                        
                        val isAdmin = className.contains("AdminActivity", ignoreCase = true) ||
                                     className.contains("AppSelectionActivity", ignoreCase = true)
                        
                        val isAllowed = if (!packageName.contains("iips.launcher", ignoreCase = true) &&
                                           !packageName.contains("settings", ignoreCase = true) &&
                                           packageName.isNotEmpty()) {
                            app?.isPackageAllowed(packageName) == true
                        } else {
                            false
                        }
                        
                        isAdmin || isAllowed
                    }
                    
                    val stillRecentlyLaunched = lastLaunchedAllowedApp != null && 
                                              (System.currentTimeMillis() - lastLaunchTime) < LOCK_TASK_DISABLE_DURATION
                    
                    !stillHasAdminOrAllowed && !stillRecentlyLaunched
                } catch (e: Exception) {
                    true
                }
                
                if (!isFinishing && !isDestroyed && stillNotAdminOrAllowed) {
                    try {
                        DeviceController.startLockTask(this)
                        // Also ensure we're the active activity
                        val intent = Intent(this, LauncherActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                                       Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                       Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(intent)
                        android.util.Log.d("LauncherActivity", "Brought launcher back in onUserLeaveHint")
                    } catch (e: Exception) {
                        // Ignore if already in lock task or launching
                    }
                } else {
                    android.util.Log.d("LauncherActivity", "Not bringing launcher back in onUserLeaveHint double-check")
                }
            }, 500) // Increased delay
        }
    }

    override fun onBackPressed() {
        // Prevent back button from exiting launcher in lockdown mode
        if (SecurePreferences.isLockdownEnabled(this)) {
            return
        }
        super.onBackPressed()
    }

    private fun setupUI() {
        appAdapter = AppGridAdapter { appInfo ->
            launchApp(appInfo)
        }

        binding.appGrid.apply {
            layoutManager = GridLayoutManager(this@LauncherActivity, 4)
            adapter = appAdapter
        }

        binding.adminButton.setOnClickListener {
            showAdminPasswordDialog()
        }
    }

    private fun setupDeviceControls() {
        // Check if Device Owner is set and admin is active
        val isDeviceOwner = DeviceAdminReceiver.isDeviceOwner(this)
        
        if (!isDeviceOwner) {
            binding.statusText.visibility = View.VISIBLE
            binding.statusText.text = getString(R.string.device_owner_not_set)
            // Don't enable security features if not Device Owner
            return
        }
        
        
        // Note: On Samsung devices, isAdminActive() can return false even when 
        // the app IS the device owner. isDeviceOwnerApp() returning true is sufficient.

        // Set organization name for device policy
        try {
            val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val componentName = DeviceAdminReceiver.getComponentName(this)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                devicePolicyManager.setOrganizationName(componentName, "www.iips.app")
            }
        } catch (e: Exception) {
            android.util.Log.w("LauncherActivity", "Could not set organization name: ${e.message}")
        }

        binding.statusText.visibility = View.GONE
        
        // Only enable security features if Device Owner and admin is active
        // Wrap in try-catch to prevent any crashes
        try {
            DeviceController.enableLockTaskMode(this)
        } catch (e: Exception) {
            // If this fails, show message but don't crash
            android.util.Log.e("LauncherActivity", "Failed to enable lock task mode: ${e.message}")
            binding.statusText.visibility = View.VISIBLE
            binding.statusText.text = "Security features not available"
        }

        // Enable comprehensive security by default - blocks Settings, prevents uninstallation
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            DeviceController.enableComprehensiveSecurity(this)
        }
        
        // Prevent force stop
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            DeviceController.preventForceStop(this)
        }

        // Always enable factory reset protection
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
            DeviceController.enableFactoryResetProtection(this)
            SecurePreferences.setFactoryResetProtectionEnabled(this, true)
        }

        // Always enable immersive mode to prevent swipe-down
        DeviceController.enableImmersiveMode(this)
        SecurePreferences.setImmersiveModeEnabled(this, true)

        // Enable lockdown by default
        DeviceController.startLockTask(this)
        SecurePreferences.setLockdownEnabled(this, true)
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val allowedApps = withContext(Dispatchers.IO) {
                database.allowedAppDao().getAll()
            }

            if (allowedApps.isEmpty()) {
                binding.emptyState.visibility = View.VISIBLE
                binding.appGrid.visibility = View.GONE
                allApps = emptyList()
                appAdapter.submitList(emptyList())
            } else {
                binding.emptyState.visibility = View.GONE
                binding.appGrid.visibility = View.VISIBLE

                val packageManager = packageManager
                val intent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }

                val resolvedApps = packageManager.queryIntentActivities(intent, 0)
                
                // Create a set of allowed package names for faster lookup (case-insensitive)
                val allowedPackageNames = allowedApps.map { it.packageName.trim().lowercase() }.toSet()
                
                allApps = resolvedApps.mapNotNull { resolveInfo ->
                    val packageName = resolveInfo.activityInfo.packageName.trim().lowercase()
                    if (allowedPackageNames.contains(packageName)) {
                        // Use original package name (not lowercased) for creating AppInfo
                        AppInfo.fromApplicationInfo(
                            resolveInfo.activityInfo.applicationInfo,
                            packageManager
                        )
                    } else {
                        null
                    }
                }.sortedBy { it.name }

                appAdapter.submitList(allApps)
            }
        }
    }

    private fun launchApp(appInfo: AppInfo) {
        try {
            android.util.Log.d("LauncherActivity", "Attempting to launch app: ${appInfo.packageName}")
            
            // Track that we're launching an allowed app
            lastLaunchedAllowedApp = appInfo.packageName.lowercase()
            lastLaunchTime = System.currentTimeMillis()
            android.util.Log.d("LauncherActivity", "Tracked allowed app launch: $lastLaunchedAllowedApp at $lastLaunchTime")
            
            // Refresh allowed apps cache before launching to ensure it's up to date
            val app = application as? com.iips.launcher.LauncherApplication
            app?.refreshAllowedApps()
            
            // Stop lock task mode before launching app to allow it to open
            if (DeviceAdminReceiver.isDeviceOwner(this)) {
                try {
                    DeviceController.stopLockTask(this)
                    android.util.Log.d("LauncherActivity", "Stopped lock task before launching app")
                    
                    // Also disable lock task mode temporarily
                    DeviceController.disableLockTaskMode(this)
                } catch (e: Exception) {
                    android.util.Log.w("LauncherActivity", "Could not stop lock task: ${e.message}")
                }
            }
            
            val launchIntent = packageManager.getLaunchIntentForPackage(appInfo.packageName)
            if (launchIntent != null) {
                // Add flags to ensure app launches properly
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                
                // Small delay to ensure lock task is fully stopped
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        startActivity(launchIntent)
                        android.util.Log.d("LauncherActivity", "Successfully launched app: ${appInfo.packageName}")
                        
                        // Log app usage
                        lifecycleScope.launch(Dispatchers.IO) {
                            val log = com.iips.launcher.data.AppUsageLog(
                                packageName = appInfo.packageName,
                                appName = appInfo.name,
                                startTime = System.currentTimeMillis()
                            )
                            database.appUsageLogDao().insert(log)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("LauncherActivity", "Error starting activity: ${e.message}", e)
                        Toast.makeText(this, "Error launching app: ${e.message}", Toast.LENGTH_SHORT).show()
                        // Clear tracking if launch failed
                        lastLaunchedAllowedApp = null
                    }
                }, 100) // Small delay to ensure everything is ready
            } else {
                android.util.Log.e("LauncherActivity", "Cannot get launch intent for: ${appInfo.packageName}")
                Toast.makeText(this, "Cannot launch ${appInfo.name}", Toast.LENGTH_SHORT).show()
                // Clear tracking if launch failed
                lastLaunchedAllowedApp = null
            }
        } catch (e: Exception) {
            android.util.Log.e("LauncherActivity", "Error launching app: ${e.message}", e)
            Toast.makeText(this, "Error launching app: ${e.message}", Toast.LENGTH_SHORT).show()
            // Clear tracking if launch failed
            lastLaunchedAllowedApp = null
        }
    }

    private fun showAdminPasswordDialog() {
        android.util.Log.d("LauncherActivity", "Showing admin password dialog")
        val dialog = AdminPasswordDialogFragment { success ->
            android.util.Log.d("LauncherActivity", "Admin password dialog callback - success: $success")
            if (success) {
                // Use post to ensure dialog is dismissed before launching activity
                Handler(Looper.getMainLooper()).postDelayed({
                    android.util.Log.d("LauncherActivity", "Launching AdminActivity after password validation")
                    
                    // Disable lock task before launching AdminActivity
                    try {
                        android.util.Log.d("LauncherActivity", "Stopping lock task")
                        DeviceController.stopLockTask(this)
                    } catch (e: Exception) {
                        android.util.Log.w("LauncherActivity", "Could not stop lock task: ${e.message}")
                    }
                    
                    // Launch AdminActivity with proper flags to ensure it opens
                    val intent = Intent(this, AdminActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    
                    try {
                        android.util.Log.d("LauncherActivity", "Starting AdminActivity with intent: $intent")
                        startActivity(intent)
                        android.util.Log.d("LauncherActivity", "AdminActivity launched successfully")
                    } catch (e: Exception) {
                        android.util.Log.e("LauncherActivity", "Failed to launch AdminActivity: ${e.message}", e)
                        Toast.makeText(this, "Failed to open Admin Panel: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }, 200) // Small delay to ensure dialog dismisses first
            } else {
                android.util.Log.d("LauncherActivity", "Password validation failed or cancelled")
            }
        }
        dialog.show(supportFragmentManager, "admin_password")
    }
    
    private fun setupStatusBar() {
        updateStatusBar()
        updateBatteryStatus()
        updateNetworkStatus()
    }
    
    private fun startStatusBarUpdates() {
        timeHandler.post(timeRunnable)
        
        // Register battery receiver
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateBatteryStatus()
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }
    
    private fun updateStatusBar() {
        val currentTime = Date()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault()) // Shorter date format
        
        binding.timeText.text = timeFormat.format(currentTime)
        binding.dateText.text = dateFormat.format(currentTime)
    }
    
    private fun updateBatteryStatus() {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        
        if (level >= 0 && scale > 0) {
            val batteryPct = (level * 100 / scale)
            val batteryText = if (isCharging) {
                "⚡$batteryPct%"
            } else {
                "$batteryPct%"
            }
            binding.batteryText.text = batteryText
        }
    }
    
    private fun updateNetworkStatus() {
        try {
            // Simple network status - can be enhanced with ConnectivityManager
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) 
                as android.net.ConnectivityManager
            val activeNetwork = connectivityManager.activeNetworkInfo
            val isConnected = activeNetwork != null && activeNetwork.isConnected
            
            val networkText = if (isConnected) {
                when (activeNetwork?.type) {
                    android.net.ConnectivityManager.TYPE_WIFI -> "WiFi"
                    android.net.ConnectivityManager.TYPE_MOBILE -> "4G"
                    else -> "Net"
                }
            } else {
                "No Net"
            }
            binding.networkText.text = networkText
        } catch (e: Exception) {
            // Permission denied or other error - show default
            binding.networkText.text = "Net"
        }
    }
    
    private fun ensureAlwaysLaunch() {
        if (DeviceAdminReceiver.isDeviceOwner(this)) {
            // Set as persistent preferred activity to always launch
            val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) 
                as android.app.admin.DevicePolicyManager
            val componentName = DeviceAdminReceiver.getComponentName(this)
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                try {
                    // Clear any other preferred activities for HOME
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                    }
                    devicePolicyManager.clearPackagePersistentPreferredActivities(
                        componentName,
                        null // Clear all
                    )
                } catch (e: Exception) {
                    // Handle exception
                }
            }
        }
    }
    
    private fun registerSettingsInterceptor() {
        // Settings blocking is handled via Device Owner restrictions
        // Removed broadcast receiver to prevent freezing/loops
    }
}

