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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.iips.launcher.R
import com.iips.launcher.data.AllowedApp
import com.iips.launcher.data.AppDatabase
import com.iips.launcher.data.AppInfo
import com.iips.launcher.databinding.ActivityLauncherBinding
import com.iips.launcher.policy.DeviceAdminReceiver
import com.iips.launcher.policy.DeviceController
import com.iips.launcher.storage.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.iips.launcher.storage.ConfigManager

@dagger.hilt.android.AndroidEntryPoint
class LauncherActivity : AppCompatActivity() {

    @javax.inject.Inject lateinit var broadcastEngine: com.iips.launcher.convergence.BroadcastRenderingEngine
    @javax.inject.Inject lateinit var configService: com.iips.launcher.network.ConfigService

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
    private var geofenceReceiver: BroadcastReceiver? = null
    
    // Track when we launch an allowed app to prevent lock task from being re-enabled
    private var lastLaunchedAllowedApp: String? = null
    private var lastLaunchTime: Long = 0
    private val LOCK_TASK_DISABLE_DURATION = 3000L // Keep lock task disabled for 3 seconds after launching app

    private var debugWipeReceiver: BroadcastReceiver? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    private var packageChangeReceiver: BroadcastReceiver? = null
    private var taskbarController: com.iips.launcher.ui.taskbar.TaskbarController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        
        // Custom exit animation for a premium feel
        splashScreen.setOnExitAnimationListener { splashScreenProvider ->
            val iconView = splashScreenProvider.iconView
            iconView.animate()
                .scaleX(1.2f)
                .scaleY(1.2f)
                .alpha(0f)
                .setDuration(500)
                .withEndAction {
                    splashScreenProvider.remove()
                }
                .start()
        }
        
        // Process provisioning extras on cold start
        com.iips.launcher.network.DeviceEnrollmentManager.extractAndPersistProvisioningExtras(this, intent)

        if (!SecurePreferences.isRegistered(this)) {
            SecurePreferences.setDeviceState(this, SecurePreferences.STATE_ONBOARDING)
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        val state = SecurePreferences.getDeviceState(this)
        when (state) {
            SecurePreferences.STATE_NEW, SecurePreferences.STATE_ONBOARDING -> {
                // Always route to OnboardingActivity — it now contains the full enrollment form
                startActivity(Intent(this, OnboardingActivity::class.java))
                finish()
                return
            }
            SecurePreferences.STATE_REGISTERED, SecurePreferences.STATE_PENDING_APPROVAL -> {
                startActivity(Intent(this, PendingActivity::class.java))
                finish()
                return
            }
        }

        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)

        setupUI()
        loadApps()
        
        // Sync configuration from server
        lifecycleScope.launch {
            // Policy sync is handled by WorkManager/MdmSocketService
        }
        
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
        
        // Initialize MDM
        checkMdmPermissions()
        initializeMdm()
        
        setupGeofenceOverlay()
        setupQuickSettings()
        setupHiddenGesture()
        setupDebugWipeReceiver()
        setupBroadcastObserver()
        setupScreenLock()
        setupPackageChangeReceiver()

        val taskbarView = binding.root.findViewById<View>(R.id.taskbar_layout)
        if (taskbarView != null) {
            taskbarController = com.iips.launcher.ui.taskbar.TaskbarController(
                activity = this,
                taskbarLayout = taskbarView,
                database = database,
                broadcastEngine = broadcastEngine,
                onShowAdminSettings = {
                    showAdminPasswordDialog()
                }
            )
        }
    }

    private fun setupPackageChangeReceiver() {
        packageChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                android.util.Log.d("LauncherActivity", "Package changed broadcast received: ${intent?.action}. Reloading apps.")
                loadApps()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        registerReceiver(packageChangeReceiver, filter)
    }

    private fun setupDebugWipeReceiver() {
        debugWipeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.dotoid.DEBUG_WIPE") {
                    android.util.Log.w("LauncherActivity", "DEBUG WIPE TRIGGERED VIA ADB")
                    Toast.makeText(this@LauncherActivity, "Debug Wipe Triggered", Toast.LENGTH_SHORT).show()
                    performSystemReset()
                }
            }
        }
        val filter = IntentFilter("com.dotoid.DEBUG_WIPE")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(debugWipeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(debugWipeReceiver, filter)
        }
    }

    private fun setupScreenLock() {
        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    android.util.Log.d("LauncherActivity", "Screen off - locking kiosk")
                    com.iips.launcher.policy.KioskLockManager.lockDevice()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenOffReceiver, filter)
    }

    private fun performSystemReset() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Cancel all WorkManager tasks
                androidx.work.WorkManager.getInstance(this@LauncherActivity).cancelAllWork()
                
                // 2. Clear Database
                database.clearAllTables()
                
                // 3. Clear Preferences
                SecurePreferences.clearAll(this@LauncherActivity)
                
                // 4. Disable Lockdown and Immersive mode
                withContext(Dispatchers.Main) {
                    com.iips.launcher.policy.DeviceController.stopLockTask(this@LauncherActivity)
                    com.iips.launcher.policy.DeviceController.disableImmersiveMode(this@LauncherActivity)
                    
                    Toast.makeText(this@LauncherActivity, "System Reset Successful. Restarting...", Toast.LENGTH_LONG).show()
                    
                    // 5. Restart Application to Onboarding
                    val intent = Intent(this@LauncherActivity, OnboardingActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.util.Log.e("LauncherActivity", "System Reset Failed", e)
                }
            }
        }
    }

    private var tapCount = 0
    private var lastTapTime = 0L
    private fun setupHiddenGesture() {
        val hiddenGestureListener = View.OnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTapTime > 500) { // Reset if more than 500ms between taps
                tapCount = 0
            }
            lastTapTime = currentTime
            tapCount++
            android.util.Log.d("LauncherActivity", "Hidden gesture tap count: $tapCount")
            if (tapCount >= 6) {
                tapCount = 0
                showAdminPasswordDialog()
            }
        }
        binding.root.setOnClickListener(hiddenGestureListener)
        binding.statusBar.setOnClickListener(hiddenGestureListener)
        binding.businessNameText.setOnClickListener(hiddenGestureListener)
    }

    private fun initializeMdm() {
        lifecycleScope.launch {
            if (SecurePreferences.isRegistered(this@LauncherActivity)) {
                com.iips.launcher.workers.TelemetryWorker.schedule(this@LauncherActivity)
                com.iips.launcher.apps.inventory.AppInventoryWorker.schedule(this@LauncherActivity)
                com.iips.launcher.guard.service.GuardLocationService.start(this@LauncherActivity)
                com.iips.launcher.guard.worker.LocationSyncWorker.schedule(this@LauncherActivity)
                com.iips.launcher.pocket.service.ApkDirectoryObserverService.start(this@LauncherActivity)
                com.iips.launcher.pocket.service.AppHealthMonitoringService.start(this@LauncherActivity)
                com.iips.launcher.pocket.sync.AppPocketSyncWorker.schedulePeriodic(this@LauncherActivity)
                startMdmService()
            }
        }
    }

    private fun startMdmService() {
        val intent = Intent(this, com.iips.launcher.network.MdmSocketService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun checkMdmPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.READ_PHONE_STATE
        )
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            permissions.add(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 1001)
        }
    }

    override fun onResume() {
        super.onResume()
        taskbarController?.onResume()
        
        // Process provisioning extras on resume
        com.iips.launcher.network.DeviceEnrollmentManager.extractAndPersistProvisioningExtras(this, intent)

        val state = SecurePreferences.getDeviceState(this)
        when (state) {
            SecurePreferences.STATE_NEW, SecurePreferences.STATE_ONBOARDING -> {
                // Always route to OnboardingActivity — it now contains the full enrollment form
                startActivity(Intent(this, OnboardingActivity::class.java))
                finish()
                return
            }
            SecurePreferences.STATE_REGISTERED, SecurePreferences.STATE_PENDING_APPROVAL -> {
                startActivity(Intent(this, PendingActivity::class.java))
                finish()
                return
            }
        }
        
        // Check if Kiosk Lock screen should be displayed
        if (com.iips.launcher.policy.KioskLockManager.isKioskLocked(this)) {
            android.util.Log.i("LauncherActivity", "Kiosk lock screen is active, redirecting...")
            val lockIntent = Intent(this, KioskLockActivity::class.java)
            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(lockIntent)
            return
        }
        
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
        
        // Force lock task if Device Owner AND Kiosk is enabled - BUT don't enable if AdminActivity, AppSelectionActivity, allowed app is visible, OR we recently launched an allowed app
        if (DeviceAdminReceiver.isDeviceOwner(this) && 
            !isAdminActivityVisible && 
            !isAppSelectionActivityVisible && 
            !isAllowedAppVisible &&
            !recentlyLaunchedAllowedApp) {
            com.iips.launcher.policy.KioskController.resumeLockTask(this)
        } else if (isAllowedAppVisible || recentlyLaunchedAllowedApp) {
            // Ensure lock task is stopped when allowed app is visible or recently launched
            try {
                com.iips.launcher.policy.KioskController.pauseLockTask(this)
                android.util.Log.d("LauncherActivity", "Stopped lock task because allowed app is visible or recently launched")
            } catch (e: Exception) {
                android.util.Log.w("LauncherActivity", "Could not stop lock task: ${e.message}")
            }
        }
        
        // Update status bar immediately
        updateStatusBar()
        
        // Check geofence lock state and mode
        val mode = SecurePreferences.getGeofenceMode(this)
        val isLocked = SecurePreferences.isGeofenceLocked(this)
        
        if (isLocked) {
            showGeofenceLock(true, "Outside authorized area")
            binding.geofencePendingBanner.visibility = View.GONE
        } else {
            showGeofenceLock(false)
            // Show banner if in enrollment mode AND we have proposed zones
            val hasProposals = SecurePreferences.getProposedZones(this).isNotEmpty()
            if (mode == "enrollment" && hasProposals) {
                binding.geofencePendingBanner.visibility = View.VISIBLE
            } else {
                binding.geofencePendingBanner.visibility = View.GONE
            }
        }

        // Check remote lock state
        val isRemoteLocked = SecurePreferences.isRemoteLocked(this)
        showRemoteLock(isRemoteLocked)
    }
    
    override fun onPause() {
        taskbarController?.onPause()
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
                        com.iips.launcher.policy.KioskController.resumeLockTask(this)
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
        taskbarController?.onDestroy()
        super.onDestroy()
        timeHandler.removeCallbacks(timeRunnable)
        batteryReceiver?.let { unregisterReceiver(it) }
        settingsReceiver?.let { unregisterReceiver(it) }
        geofenceReceiver?.let { unregisterReceiver(it) }
        debugWipeReceiver?.let { unregisterReceiver(it) }
        screenOffReceiver?.let { unregisterReceiver(it) }
        packageChangeReceiver?.let { unregisterReceiver(it) }
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
                com.iips.launcher.policy.KioskController.resumeLockTask(this)
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
                        com.iips.launcher.policy.KioskController.resumeLockTask(this)
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
        if (SecurePreferences.isGeofenceLocked(this) || SecurePreferences.isRemoteLocked(this)) {
            return
        }
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
        
        binding.qsAdminButton.setOnClickListener {
            toggleQuickSettings(false)
            showAdminPasswordDialog()
        }

        binding.btnAppPocket.setOnClickListener {
            val intent = Intent(this, com.iips.launcher.pocket.ui.AppPocketActivity::class.java)
            startActivity(intent)
        }
    }

    private var qsStartY = 0f
    private var isQsOpen = false
    private val QS_HEIGHT_DP = 300 // Max height to show

    private fun setupQuickSettings() {
        val density = resources.displayMetrics.density
        val qsMaxTranslation = 0f
        val qsMinTranslation = -600 * density
        var qsStartX = 0f
        var qsDownTime = 0L

        binding.qsDragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    qsStartY = event.rawY
                    qsStartX = event.rawX
                    qsDownTime = System.currentTimeMillis()
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - qsStartY
                    if (deltaY > 0 || isQsOpen) {
                        val newTranslation = if (isQsOpen) deltaY else qsMinTranslation + deltaY
                        binding.quickSettingsPanel.translationY = newTranslation.coerceIn(qsMinTranslation, qsMaxTranslation)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val deltaY = event.rawY - qsStartY
                    val deltaX = event.rawX - qsStartX
                    val duration = System.currentTimeMillis() - qsDownTime
                    
                    // Check if it's a tap gesture (short duration, small movement)
                    if (duration < 250 && Math.abs(deltaY) < 15 && Math.abs(deltaX) < 15) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastTapTime > 500) {
                            tapCount = 0
                        }
                        lastTapTime = currentTime
                        tapCount++
                        android.util.Log.d("LauncherActivity", "Drag handle tap count: $tapCount")
                        if (tapCount >= 6) {
                            tapCount = 0
                            showAdminPasswordDialog()
                        }
                    } else {
                        val currentTranslation = binding.quickSettingsPanel.translationY
                        val threshold = (qsMinTranslation + qsMaxTranslation) / 2
                        if (currentTranslation > threshold) {
                            animateQuickSettings(true)
                        } else {
                            animateQuickSettings(false)
                        }
                    }
                    true
                }
                else -> false
            }
        }
        
        // Also allow closing by clicking background or handle when open
        binding.quickSettingsPanel.setOnClickListener { 
            // Prevent clicks from passing through
        }
        
        binding.root.setOnTouchListener { _, event ->
            if (isQsOpen && event.action == android.view.MotionEvent.ACTION_DOWN) {
                animateQuickSettings(false)
                true
            } else {
                false
            }
        }
    }

    private fun animateQuickSettings(open: Boolean) {
        val density = resources.displayMetrics.density
        val targetY = if (open) 0f else -600 * density
        
        binding.quickSettingsPanel.animate()
            .translationY(targetY)
            .setDuration(300)
            .withEndAction {
                isQsOpen = open
            }
            .start()
    }

    private fun toggleQuickSettings(open: Boolean) {
        animateQuickSettings(open)
    }

    private fun setupGeofenceOverlay() {
        binding.btnRetryLocation.setOnClickListener {
            // Force a location check by restarting the service or sending an intent
            com.iips.launcher.policy.GeofenceService.start(this)
            Toast.makeText(this, "Checking location...", Toast.LENGTH_SHORT).show()
        }

        binding.btnRequestLocationAuth.setOnClickListener {
            requestLocationAuthorization()
        }

        binding.btnAdminUnlock.setOnClickListener {
            showAdminPasswordDialog()
        }

        binding.btnRemoteAdminUnlock.setOnClickListener {
            showAdminPasswordDialog()
        }

        // Register receiver for geofence and remote lock events
        geofenceReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    com.iips.launcher.policy.GeofenceService.ACTION_GEOFENCE_LOCK -> {
                        val reason = intent.getStringExtra(com.iips.launcher.policy.GeofenceService.EXTRA_REASON)
                        showGeofenceLock(true, reason)
                    }
                    com.iips.launcher.policy.GeofenceService.ACTION_GEOFENCE_UNLOCK -> {
                        showGeofenceLock(false)
                    }
                    "com.iips.launcher.ACTION_REMOTE_LOCK" -> {
                        showRemoteLock(true)
                    }
                    "com.iips.launcher.ACTION_REMOTE_UNLOCK" -> {
                        showRemoteLock(false)
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(com.iips.launcher.policy.GeofenceService.ACTION_GEOFENCE_LOCK)
            addAction(com.iips.launcher.policy.GeofenceService.ACTION_GEOFENCE_UNLOCK)
            addAction("com.iips.launcher.ACTION_REMOTE_LOCK")
            addAction("com.iips.launcher.ACTION_REMOTE_UNLOCK")
        }
        registerReceiver(geofenceReceiver, filter)
    }

    private fun showGeofenceLock(locked: Boolean, reason: String? = null) {
        if (locked) {
            binding.geofenceLockOverlay.visibility = View.VISIBLE
            binding.lockMessage.text = reason ?: getString(R.string.geofence_out_of_range)
            
            // If locked by geofence, ensure lock task is on
            if (DeviceAdminReceiver.isDeviceOwner(this)) {
                com.iips.launcher.policy.KioskController.resumeLockTask(this)
            }
        } else {
            binding.geofenceLockOverlay.visibility = View.GONE
        }
    }

    private fun showRemoteLock(locked: Boolean) {
        if (locked) {
            binding.remoteLockOverlay.visibility = View.VISIBLE
            if (DeviceAdminReceiver.isDeviceOwner(this)) {
                com.iips.launcher.policy.KioskController.resumeLockTask(this)
            }
        } else {
            binding.remoteLockOverlay.visibility = View.GONE
        }
    }

    private fun requestLocationAuthorization() {
        if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission is required for authorization request", Toast.LENGTH_LONG).show()
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 101)
            return
        }

        Toast.makeText(this, "Acquiring current GPS coordinates...", Toast.LENGTH_SHORT).show()
        val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
        
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    sendLocationAuthProposal(location)
                } else {
                    val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
                        com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 1000L
                    ).setMaxUpdates(1).build()
                    
                    val callback = object : com.google.android.gms.location.LocationCallback() {
                        override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                            val loc = result.lastLocation
                            if (loc != null) {
                                sendLocationAuthProposal(loc)
                            } else {
                                Toast.makeText(this@LauncherActivity, "Could not acquire GPS lock. Please retry.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    fusedLocationClient.requestLocationUpdates(
                        locationRequest, callback, android.os.Looper.getMainLooper()
                    )
                }
            }.addOnFailureListener { e ->
                Toast.makeText(this, "Failed to get location: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Security exception: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendLocationAuthProposal(location: android.location.Location) {
        lifecycleScope.launch {
            try {
                val token = SecurePreferences.getDeviceToken(this@LauncherActivity)
                if (token == null) {
                    Toast.makeText(this@LauncherActivity, "Device token not found. Please register device first.", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val proposedRule = com.iips.launcher.network.models.GeofenceRule(
                    id = "auth-req-${System.currentTimeMillis()}",
                    name = "Authorized Site Request",
                    lat = location.latitude,
                    lng = location.longitude,
                    radius_m = 30.0
                )
                val payload = mapOf("zones" to listOf(proposedRule))
                val request = com.iips.launcher.network.models.MdmEventRequest(
                    type = "GEOFENCE_PROPOSAL",
                    payload = payload
                )

                val response = configService.sendEvent("Bearer $token", request)
                if (response.isSuccessful) {
                    Toast.makeText(this@LauncherActivity, "Location proposal sent successfully to Quasar!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@LauncherActivity, "Server rejected request (Code: ${response.code()})", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@LauncherActivity, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
            }
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
                devicePolicyManager.setOrganizationName(componentName, "www.dotoid.com")
            }
        } catch (e: Exception) {
            android.util.Log.w("LauncherActivity", "Could not set organization name: ${e.message}")
        }

        binding.statusText.visibility = View.GONE
        
        // Always enable factory reset protection
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
            DeviceController.enableFactoryResetProtection(this)
            SecurePreferences.setFactoryResetProtectionEnabled(this, true)
        }

        // Apply dynamic kiosk policy
        com.iips.launcher.policy.KioskController.applyPolicy(this)

        // Always enable immersive mode to prevent swipe-down
        DeviceController.enableImmersiveMode(this)
        SecurePreferences.setImmersiveModeEnabled(this, true)

        // Lock the system status bar (top bar) to prevent drag-down
        DeviceController.setStatusBarLocked(this, true)
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val allowedApps = withContext(Dispatchers.IO) {
                database.appPolicyDao().getAllPolicies()
            }
            val localAllowedApps = withContext(Dispatchers.IO) {
                database.allowedAppDao().getAll()
            }
            val pocketApps = withContext(Dispatchers.IO) {
                database.appPocketDao().getAllApps()
            }
            val pocketInstalledApps = pocketApps.filter { it.status == "INSTALLED" }

            binding.emptyState.visibility = View.GONE
            binding.appGrid.visibility = View.VISIBLE

            val packageManager = packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolvedApps = packageManager.queryIntentActivities(intent, 0)
            
            // Filter rule: Only display apps where:
            // 1. Policy mode is REQUIRED or (ALLOWED and pinned)
            // 2. Locally allowed in allowed_apps DB
            // 3. Installed from App Pocket
            val allowedPackageNames = mutableSetOf<String>()
            allowedApps
                .filter { it.mode.uppercase() == "REQUIRED" || (it.mode.uppercase() == "ALLOWED" && it.pinned) }
                .forEach { allowedPackageNames.add(it.packageName.trim().lowercase()) }
            localAllowedApps
                .forEach { allowedPackageNames.add(it.packageName.trim().lowercase()) }
            pocketInstalledApps
                .forEach { allowedPackageNames.add(it.packageName.trim().lowercase()) }
            
            val sortedApps = resolvedApps.mapNotNull { resolveInfo ->
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

            val appPocketInfo = AppInfo(
                packageName = "com.iips.launcher.pocket",
                name = "App Pocket",
                icon = androidx.core.content.ContextCompat.getDrawable(this@LauncherActivity, R.drawable.ic_app_pocket),
                isSystemApp = true
            )

            val finalAppsList = mutableListOf<AppInfo>()
            finalAppsList.add(appPocketInfo)
            finalAppsList.addAll(sortedApps)

            allApps = finalAppsList
            appAdapter.submitList(finalAppsList)
        }
    }

    private fun launchApp(appInfo: AppInfo) {
        if (appInfo.packageName == "com.iips.launcher.pocket") {
            try {
                val intent = Intent(this, com.iips.launcher.pocket.ui.AppPocketActivity::class.java)
                startActivity(intent)
            } catch (e: Exception) {
                android.util.Log.e("LauncherActivity", "Error launching AppPocket: ${e.message}", e)
                Toast.makeText(this, "Error launching App Pocket", Toast.LENGTH_SHORT).show()
            }
            return
        }
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
                        taskbarController?.addRecentApp(appInfo.packageName)
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
        val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())
        val sdfDate = SimpleDateFormat("MMM d", Locale.getDefault())
        val now = Date()

        binding.timeText.text = sdfTime.format(now)
        binding.dateText.text = sdfDate.format(now)

        // Business Owner Name
        val businessName = SecurePreferences.getBusinessName(this)
        binding.businessNameText.text = businessName ?: ""

        // Battery
        val batteryInfo = com.iips.launcher.core.HardwareProvider.getBatteryInfo(this)
        val batteryText = "${batteryInfo.level}%"
        binding.batteryText.text = batteryText
        binding.qsBatteryStatus.text = "Battery: $batteryText${if (batteryInfo.charging) " (Charging)" else ""}"
        
        if (batteryInfo.level < 20) {
            binding.qsBatteryIcon.setImageResource(android.R.drawable.ic_lock_idle_low_battery)
        } else {
            binding.qsBatteryIcon.setImageResource(android.R.drawable.ic_lock_idle_charging)
        }

        // Network
        val networkInfo = com.iips.launcher.core.HardwareProvider.getNetworkInfo(this)
        binding.networkText.text = networkInfo.type
        binding.qsWifiStatus.text = "Network: ${networkInfo.type}"
        
        if (networkInfo.type == "WIFI") {
            binding.qsWifiIcon.setImageResource(android.R.drawable.ic_menu_compass)
        } else {
            binding.qsWifiIcon.setImageResource(android.R.drawable.ic_menu_mylocation)
        }
        
        // SIM Status (Deprecated in heartbeat, showing static label or hiding)
        binding.simText.visibility = View.GONE
        binding.qsSimStatus.text = "Cellular: Ready"
        binding.qsSimIcon.setImageResource(android.R.drawable.ic_menu_call)
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

    private val gson = com.google.gson.Gson()

    private fun setupBroadcastObserver() {
        lifecycleScope.launch {
            broadcastEngine.activeBroadcast.collect { payload ->
                if (payload != null) {
                    renderBroadcast(payload)
                } else {
                    hideBroadcastOverlays()
                }
            }
        }
    }

    private fun renderBroadcast(payload: com.iips.launcher.convergence.BroadcastPayload) {
        val blockingContainer = binding.root.findViewById<View>(R.id.broadcast_blocking_container)
        val bannerContainer = binding.root.findViewById<View>(R.id.broadcast_banner_container)
        
        when (payload.launcherMode.lowercase()) {
            "silent" -> {
                // Background telemetry only
                hideBroadcastOverlays()
            }
            "toast" -> {
                hideBroadcastOverlays()
                Toast.makeText(this, "${payload.title}: ${payload.message}", Toast.LENGTH_LONG).show()
            }
            "banner" -> {
                blockingContainer?.visibility = View.GONE
                bannerContainer?.apply {
                    visibility = View.VISIBLE
                    alpha = 0f
                    translationY = -100f
                    animate().alpha(1f).translationY(0f).setDuration(300).start()
                }
                
                binding.root.findViewById<android.widget.TextView>(R.id.broadcast_banner_title)?.text = payload.title
                binding.root.findViewById<android.widget.TextView>(R.id.broadcast_banner_message)?.text = payload.message
                
                binding.root.findViewById<View>(R.id.broadcast_banner_close)?.setOnClickListener {
                    bannerContainer?.animate()?.alpha(0f)?.translationY(-100f)?.setDuration(200)?.withEndAction {
                        bannerContainer.visibility = View.GONE
                        broadcastEngine.acknowledgeBroadcast(payload.broadcastId)
                    }?.start()
                }
            }
            "blocking", "kiosk-lock" -> {
                bannerContainer?.visibility = View.GONE
                blockingContainer?.apply {
                    visibility = View.VISIBLE
                    alpha = 0f
                    animate().alpha(1f).setDuration(300).start()
                }
                
                binding.root.findViewById<android.widget.TextView>(R.id.broadcast_blocking_title)?.text = payload.title
                binding.root.findViewById<android.widget.TextView>(R.id.broadcast_blocking_message)?.text = payload.message
                
                val ackBtn = binding.root.findViewById<View>(R.id.broadcast_blocking_ack_button)
                if (payload.requiresAcknowledgement) {
                    ackBtn?.visibility = View.VISIBLE
                    ackBtn?.setOnClickListener {
                        blockingContainer?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
                            blockingContainer.visibility = View.GONE
                            broadcastEngine.acknowledgeBroadcast(payload.broadcastId)
                        }?.start()
                    }
                } else {
                    ackBtn?.visibility = View.GONE
                }
            }
        }
    }

    private fun hideBroadcastOverlays() {
        binding.root.findViewById<View>(R.id.broadcast_blocking_container)?.visibility = View.GONE
        binding.root.findViewById<View>(R.id.broadcast_banner_container)?.visibility = View.GONE
    }
}

