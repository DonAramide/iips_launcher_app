package com.iips.launcher.ui.taskbar

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iips.launcher.R
import com.iips.launcher.data.AppDatabase
import com.iips.launcher.data.AllowedApp
import com.iips.launcher.ui.AdminPasswordDialogFragment
import com.iips.launcher.ui.LauncherPairingActivity
import com.iips.launcher.convergence.BroadcastRenderingEngine
import com.iips.launcher.storage.SecurePreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class TaskbarController(
    private val activity: FragmentActivity,
    private val taskbarLayout: View,
    private val database: AppDatabase,
    private val broadcastEngine: BroadcastRenderingEngine,
    private val onShowAdminSettings: () -> Unit
) {
    companion object {
        private const val TAG = "TaskbarController"
        private const val POLLING_INTERVAL_MS = 25000L // 25 seconds polling interval as requested
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val repository = WorkspaceStateRepository(activity)
    private val pm = activity.packageManager
    private val usageStatsManager = activity.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    private val workspaceRecycler: RecyclerView = taskbarLayout.findViewById(R.id.workspace_recycler)
    private val btnPocket: ImageView = taskbarLayout.findViewById(R.id.btn_taskbar_pocket)
    private val btnAlerts: View = taskbarLayout.findViewById(R.id.btn_taskbar_alerts)
    private val txtAlertsBadge: TextView = taskbarLayout.findViewById(R.id.txt_alerts_badge)
    private val btnGuard: View = taskbarLayout.findViewById(R.id.btn_taskbar_guard)
    private val imgGuardStatus: ImageView = taskbarLayout.findViewById(R.id.img_guard_status)
    private val txtGuardBadge: TextView = taskbarLayout.findViewById(R.id.txt_guard_badge)
    private val btnStatus: ImageView = taskbarLayout.findViewById(R.id.btn_taskbar_status)

    private lateinit var adapter: WorkspaceAdapter
    private var pollingJob: Job? = null
    
    // Cache of allowed packages to filter taskbar
    private var allowedPackages = emptySet<String>()
    
    // Full compiled list of current workspace items
    private var workspaceItems = emptyList<WorkspaceItem>()
    private var lastActiveAppPackage: String? = null

    init {
        setupRecyclerView()
        setupStaticControls()
        observeAlertsAndGuard()
    }

    private fun setupRecyclerView() {
        workspaceRecycler.layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
        
        adapter = WorkspaceAdapter(
            onAppClick = { item ->
                launchOrSwitchToApp(item.packageName)
            },
            onAppLongClick = { item, view ->
                showAppContextMenu(item, view)
            },
            onOverflowClick = { view ->
                showOverflowMenu(view)
            }
        )
        workspaceRecycler.adapter = adapter
    }

    private fun setupStaticControls() {
        // App Pocket Navigation
        btnPocket.setOnClickListener {
            try {
                val intent = Intent(activity, com.iips.launcher.pocket.ui.AppPocketActivity::class.java)
                activity.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error launching AppPocketActivity", e)
                Toast.makeText(activity, "App Pocket unavailable", Toast.LENGTH_SHORT).show()
            }
        }

        // Alerts Widget Click (Trigger acknowledgement or display active blocking alerts)
        btnAlerts.setOnClickListener {
            val active = broadcastEngine.activeBroadcast.value
            if (active != null) {
                // Trigger overlay alert display context in LauncherActivity
                Toast.makeText(activity, "Active Alert: ${active.title}\n${active.message}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(activity, "No active alerts", Toast.LENGTH_SHORT).show()
            }
        }

        // Guard Widget Click (Launch pairing / device enrollment)
        btnGuard.setOnClickListener {
            try {
                val intent = Intent(activity, LauncherPairingActivity::class.java)
                activity.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error launching pairing activity", e)
                Toast.makeText(activity, "Guard Pairing unavailable", Toast.LENGTH_SHORT).show()
            }
        }

        // Device Status Widget Click (Show the Status dialog popup)
        btnStatus.setOnClickListener {
            val dialog = DeviceStatusDialogFragment {
                onShowAdminSettings()
            }
            dialog.show(activity.supportFragmentManager, "device_status_popup")
        }
    }

    fun onResume() {
        startPolling()
    }

    fun onPause() {
        stopPolling()
    }

    fun onDestroy() {
        scope.cancel()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    updateWorkspaceState()
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating workspace state", e)
                }
                delay(POLLING_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun updateWorkspaceState() {
        // 1. Fetch policy-allowed applications from all three sources to match LauncherActivity grid
        val allowedAppsFromPolicy = withContext(Dispatchers.IO) {
            database.appPolicyDao().getAllPolicies()
        }
        val localAllowedApps = withContext(Dispatchers.IO) {
            database.allowedAppDao().getAll()
        }
        val pocketApps = withContext(Dispatchers.IO) {
            database.appPocketDao().getAllApps()
        }

        val allowedPackageNamesSet = mutableSetOf<String>()
        allowedAppsFromPolicy
            .filter { it.mode.uppercase() == "REQUIRED" || (it.mode.uppercase() == "ALLOWED" && it.pinned) }
            .forEach { allowedPackageNamesSet.add(it.packageName.trim().lowercase()) }
        localAllowedApps
            .forEach { allowedPackageNamesSet.add(it.packageName.trim().lowercase()) }
        pocketApps
            .filter { it.status == "INSTALLED" }
            .forEach { allowedPackageNamesSet.add(it.packageName.trim().lowercase()) }

        allowedPackages = allowedPackageNamesSet

        // Get currently foreground package
        val foregroundPkg = getForegroundPackage()

        // If the foreground package is an allowed package, add it to recent apps and update lastActiveAppPackage
        if (foregroundPkg != null && allowedPackages.contains(foregroundPkg.lowercase())) {
            lastActiveAppPackage = foregroundPkg
            repository.addRecentApp(foregroundPkg)
        }

        // Determine activePkg to highlight with indicator dot
        val activePkg = if (foregroundPkg != null && allowedPackages.contains(foregroundPkg.lowercase())) {
            foregroundPkg
        } else {
            // Fall back to the most recently active app package
            lastActiveAppPackage ?: repository.getRecentApps().lastOrNull { allowedPackages.contains(it.lowercase()) }
        }

        // 2. Fetch running applications in the last 2 minutes
        val runningPackages = getRunningPackages()

        // 3. Update recent apps memory with currently running allowed apps
        runningPackages.forEach { pkg ->
            if (allowedPackages.contains(pkg.lowercase())) {
                repository.addRecentApp(pkg)
            }
        }

        // 4. Compile Pinned and Recent lists
        val pinned = repository.getPinnedApps().filter { allowedPackages.contains(it.lowercase()) }
        val recents = repository.getRecentApps().filter { allowedPackages.contains(it.lowercase()) && !pinned.contains(it) }

        // Combine packages
        val allPackages = (pinned + recents).distinct().toMutableList()
        if (foregroundPkg != null && allowedPackages.contains(foregroundPkg.lowercase()) && !allPackages.contains(foregroundPkg)) {
            allPackages.add(foregroundPkg)
        }

        // 5. Load App details (Name, Icon drawable) asynchronously
        val items = withContext(Dispatchers.IO) {
            allPackages.map { pkg ->
                var appName = pkg
                var icon: android.graphics.drawable.Drawable? = null
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    appName = pm.getApplicationLabel(appInfo).toString()
                    icon = pm.getApplicationIcon(appInfo)
                } catch (e: Exception) {
                    // App might be uninstalled
                }
                WorkspaceItem(
                    packageName = pkg,
                    name = appName,
                    icon = icon,
                    isRunning = (pkg != null && pkg == activePkg),
                    isPinned = pinned.contains(pkg)
                )
            }
        }

        workspaceItems = items

        // 6. Format with max capacity layout logic (Cap at 5 items)
        val visibleItems = mutableListOf<WorkspaceItem>()
        if (items.size <= 5) {
            visibleItems.addAll(items)
        } else {
            // Take first 4 items
            visibleItems.addAll(items.take(4))
            // 5th is overflow item
            visibleItems.add(
                WorkspaceItem(
                    packageName = "overflow",
                    name = "More",
                    icon = null,
                    isRunning = false,
                    isPinned = false,
                    isOverflow = true,
                    overflowCount = items.size - 4
                )
            )
        }

        withContext(Dispatchers.Main) {
            adapter.submitList(visibleItems)
            updateGuardStatus()
        }
    }

    private fun getForegroundPackage(): String? {
        try {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningTasks = am.getRunningTasks(1)
            if (runningTasks.isNotEmpty()) {
                val topActivity = runningTasks[0].topActivity
                val pkg = topActivity?.packageName
                if (pkg != null && !pkg.contains("iips.launcher", ignoreCase = true)) {
                    return pkg
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting foreground package", e)
        }
        return null
    }

    fun addRecentApp(packageName: String) {
        scope.launch {
            if (allowedPackages.isEmpty()) {
                val allowedAppsFromPolicy = withContext(Dispatchers.IO) {
                    database.appPolicyDao().getAllPolicies()
                }
                val localAllowedApps = withContext(Dispatchers.IO) {
                    database.allowedAppDao().getAll()
                }
                val pocketApps = withContext(Dispatchers.IO) {
                    database.appPocketDao().getAllApps()
                }

                val allowedPackageNamesSet = mutableSetOf<String>()
                allowedAppsFromPolicy
                    .filter { it.mode.uppercase() == "REQUIRED" || (it.mode.uppercase() == "ALLOWED" && it.pinned) }
                    .forEach { allowedPackageNamesSet.add(it.packageName.trim().lowercase()) }
                localAllowedApps
                    .forEach { allowedPackageNamesSet.add(it.packageName.trim().lowercase()) }
                pocketApps
                    .filter { it.status == "INSTALLED" }
                    .forEach { allowedPackageNamesSet.add(it.packageName.trim().lowercase()) }

                allowedPackages = allowedPackageNamesSet
            }
            if (allowedPackages.contains(packageName.lowercase())) {
                repository.addRecentApp(packageName)
                updateWorkspaceState()
            }
        }
    }

    private fun getRunningPackages(): Set<String> {
        if (usageStatsManager == null) return emptySet()
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 1000 * 60 * 2 // 2 minutes window
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startTime, endTime)
        if (stats.isNullOrEmpty()) return emptySet()
        
        return stats.filter { it.lastTimeUsed > startTime }.map { it.packageName }.toSet()
    }

    private fun launchOrSwitchToApp(packageName: String) {
        try {
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
                repository.addRecentApp(packageName)
                scope.launch { updateWorkspaceState() }
            } else {
                Toast.makeText(activity, "App cannot be launched", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app: $packageName", e)
        }
    }

    private fun showAppContextMenu(item: WorkspaceItem, view: View) {
        val popup = PopupMenu(activity, view)
        
        val pinTitle = if (item.isPinned) "Unpin from Workspace" else "Pin to Workspace"
        popup.menu.add(0, 1, 0, pinTitle)
        popup.menu.add(0, 2, 1, "Restart Workspace App (Admin Only)")

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> {
                    if (item.isPinned) {
                        repository.unpinApp(item.packageName)
                    } else {
                        repository.pinApp(item.packageName)
                    }
                    scope.launch { updateWorkspaceState() }
                    true
                }
                2 -> {
                    // Admin authentication prompt
                    val dialog = AdminPasswordDialogFragment { success ->
                        if (success) {
                            showRestartConfirmationDialog(item)
                        }
                    }
                    dialog.show(activity.supportFragmentManager, "admin_auth_restart")
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showRestartConfirmationDialog(item: WorkspaceItem) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Restart ${item.name}?")
            .setMessage("Unsaved work in the application may be lost.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restart") { _, _ ->
                restartApp(item.packageName)
            }
            .show()
    }

    private fun restartApp(packageName: String) {
        try {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            // Relaunch the intent with clear task flags (acts as restart)
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                
                // Relaunch app cleanly
                activity.startActivity(intent)
                Toast.makeText(activity, "Relaunching ${packageName}...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, "Failed to locate app launch intent", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting app: $packageName", e)
        }
    }

    private fun showOverflowMenu(view: View) {
        val popup = PopupMenu(activity, view)
        // Add all overflowed items (index 4 and onwards)
        val overflowItems = workspaceItems.drop(4)
        
        overflowItems.forEachIndexed { index, item ->
            popup.menu.add(0, index, 0, item.name)
        }

        popup.setOnMenuItemClickListener { menuItem ->
            val clickedItem = overflowItems.getOrNull(menuItem.itemId)
            if (clickedItem != null) {
                launchOrSwitchToApp(clickedItem.packageName)
                true
            } else {
                false
            }
        }
        popup.show()
    }

    private fun observeAlertsAndGuard() {
        // Collect alerts StateFlow in coroutine scope
        activity.lifecycleScope.launch {
            broadcastEngine.activeBroadcast.collectLatest { payload ->
                if (payload != null) {
                    txtAlertsBadge.visibility = View.VISIBLE
                    txtAlertsBadge.text = "1"
                } else {
                    txtAlertsBadge.visibility = View.GONE
                }
            }
        }

        // Periodically check Guard Database events for count badge
        scope.launch {
            while (isActive) {
                try {
                    val unsyncedCount = withContext(Dispatchers.IO) {
                        database.guardEventDao().getUnsyncedEvents().size
                    }
                    if (unsyncedCount > 0) {
                        txtGuardBadge.visibility = View.VISIBLE
                        txtGuardBadge.text = unsyncedCount.toString()
                    } else {
                        txtGuardBadge.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating Guard badge count", e)
                }
                delay(30000L) // 30 seconds interval for secondary badges
            }
        }
    }

    private fun updateGuardStatus() {
        val deviceState = SecurePreferences.getDeviceState(activity)
        if (deviceState == SecurePreferences.STATE_ACTIVE) {
            imgGuardStatus.setColorFilter(activity.getColor(android.R.color.holo_green_light))
        } else if (deviceState == SecurePreferences.STATE_LOCKED) {
            imgGuardStatus.setColorFilter(activity.getColor(android.R.color.holo_red_light))
        } else {
            imgGuardStatus.setColorFilter(activity.getColor(android.R.color.holo_orange_light))
        }
    }
}
