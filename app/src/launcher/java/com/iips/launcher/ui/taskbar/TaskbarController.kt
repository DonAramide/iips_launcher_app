package com.iips.launcher.ui.taskbar

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iips.launcher.R
import com.iips.launcher.convergence.BroadcastRenderingEngine
import com.iips.launcher.data.AppDatabase
import com.iips.launcher.ui.AdminPasswordDialogFragment
import com.iips.launcher.ui.LauncherPairingActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class TaskbarController(
    private val activity: FragmentActivity,
    private val taskbarLayout: View,
    private val database: AppDatabase,
    private val broadcastEngine: BroadcastRenderingEngine,
    private val pairingService: com.iips.launcher.network.LauncherPairingService,
    private val onShowAdminSettings: () -> Unit,
    private val drawerLayout: androidx.drawerlayout.widget.DrawerLayout? = null // kept for compat, unused
) {
    companion object {
        private const val TAG = "TaskbarController"
        private const val POLLING_INTERVAL_MS = 25000L
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val repository = WorkspaceStateRepository(activity)
    private val pm = activity.packageManager
    private val usageStatsManager =
        activity.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    private val workspaceRecycler: RecyclerView =
        taskbarLayout.findViewById(R.id.workspace_recycler)
    private val btnDotroid: ImageView = taskbarLayout.findViewById(R.id.btn_taskbar_dotroid)
    private val btnAlerts: View = taskbarLayout.findViewById(R.id.btn_taskbar_alerts)
    private val txtAlertsBadge: TextView = taskbarLayout.findViewById(R.id.txt_alerts_badge)
    private val btnStatus: ImageView = taskbarLayout.findViewById(R.id.btn_taskbar_status)

    private lateinit var adapter: WorkspaceAdapter
    private var pollingJob: Job? = null

    // Popup window reference (so we can dismiss before re-opening)
    private var dotroidPopup: PopupWindow? = null

    private var allowedPackages = emptySet<String>()
    private var workspaceItems = emptyList<WorkspaceItem>()
    private var lastActiveAppPackage: String? = null

    init {
        setupRecyclerView()
        setupStaticControls()
        observeAlertsAndGuard()
    }

    private fun setupRecyclerView() {
        workspaceRecycler.layoutManager =
            LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
        adapter = WorkspaceAdapter(
            onAppClick = { item -> launchOrSwitchToApp(item.packageName) },
            onAppLongClick = { item, view -> showAppContextMenu(item, view) },
            onOverflowClick = { view -> showOverflowMenu(view) }
        )
        workspaceRecycler.adapter = adapter
    }

    private fun setupStaticControls() {
        // Dotroid icon → compact popup menu (click only, no swipe)
        btnDotroid.setOnClickListener { anchor ->
            showDotroidPopup(anchor)
        }

        btnAlerts.setOnClickListener {
            val active = broadcastEngine.activeBroadcast.value
            if (active != null) {
                Toast.makeText(
                    activity,
                    "Active Alert: ${active.title}\n${active.message}",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(activity, "No active alerts", Toast.LENGTH_SHORT).show()
            }
        }

        btnStatus.setOnClickListener {
            val dialog = DeviceStatusDialogFragment { onShowAdminSettings() }
            dialog.show(activity.supportFragmentManager, "device_status_popup")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Dotroid compact popup menu (Windows Start-menu style)
    // ─────────────────────────────────────────────────────────────
    private fun showDotroidPopup(anchor: View) {
        // Dismiss any existing popup first
        dotroidPopup?.dismiss()

        val inflater = LayoutInflater.from(activity)
        val popupView = inflater.inflate(R.layout.popup_dotroid_menu, null)

        // Measure popup so we can place it above the anchor
        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupHeight = popupView.measuredHeight
        val popupWidth  = popupView.measuredWidth

        val popup = PopupWindow(
            popupView,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true // focusable — dismisses on outside touch
        )
        popup.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        popup.isOutsideTouchable = true   // tap outside to dismiss
        popup.isTouchable = true
        // ← NO setTouchInterceptor that would let swipe open it

        dotroidPopup = popup

        // Wire up menu items
        popupView.findViewById<View>(R.id.popup_pair_manager).setOnClickListener {
            popup.dismiss()
            try {
                activity.startActivity(
                    Intent(activity, LauncherPairingActivity::class.java)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error launching pairing", e)
                Toast.makeText(activity, "Pair Manager unavailable", Toast.LENGTH_SHORT).show()
            }
        }

        popupView.findViewById<View>(R.id.popup_settings).setOnClickListener {
            popup.dismiss()
            onShowAdminSettings()
        }

        val sendMsgItem = popupView.findViewById<View>(R.id.popup_send_message)
        if (com.iips.launcher.storage.SecurePreferences.isRegistered(activity)) {
            sendMsgItem.visibility = View.VISIBLE
            sendMsgItem.setOnClickListener {
                popup.dismiss()
                showSendManagerMessageDialog()
            }
        } else {
            sendMsgItem.visibility = View.GONE
        }

        popupView.findViewById<View>(R.id.popup_app_pocket).setOnClickListener {
            popup.dismiss()
            try {
                activity.startActivity(
                    Intent(activity, com.iips.launcher.pocket.ui.AppPocketActivity::class.java)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error launching AppPocket", e)
                Toast.makeText(activity, "App Pocket unavailable", Toast.LENGTH_SHORT).show()
            }
        }

        // Position: above the anchor icon, aligned to its left edge
        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)

        // Show above the taskbar icon
        val offsetY = -(popupHeight + anchor.height + 8)   // 8dp gap above icon
        val offsetX = 0                                      // align to left of icon

        popup.showAtLocation(
            anchor,
            Gravity.NO_GRAVITY,
            anchorLoc[0] + offsetX,
            anchorLoc[1] + offsetY
        )
    }

    private fun showSendManagerMessageDialog() {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_send_manager_msg, null)
        val titleInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_manager_msg_title)
        val bodyInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_manager_msg_body)

        MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setCancelable(false)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ ->
                val title = titleInput.text.toString().trim()
                val body = bodyInput.text.toString().trim()
                if (title.isNotEmpty() && body.isNotEmpty()) {
                    sendMessageToManager(title, body)
                } else {
                    Toast.makeText(activity, "Message title and body cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun sendMessageToManager(title: String, message: String) {
        scope.launch {
            try {
                val deviceId = com.iips.launcher.storage.SecurePreferences.getDeviceId(activity) ?: ""
                val authHeader = "Bearer ${com.iips.launcher.storage.SecurePreferences.getDeviceToken(activity)}"
                
                val request = com.iips.launcher.network.models.ManagerNotifyRequest(title, message)
                val response = pairingService.notifyManager(deviceId, authHeader, request)
                
                if (response.isSuccessful && response.body()?.responseCode == "00") {
                    Toast.makeText(activity, "Message sent to manager", Toast.LENGTH_SHORT).show()
                } else {
                    Log.e(TAG, "Failed to send message: ${response.errorBody()?.string()}")
                    Toast.makeText(activity, "Failed to send message", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message to manager", e)
                Toast.makeText(activity, "Failed to send message", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────
    fun onResume() { startPolling() }
    fun onPause()  { stopPolling() }
    fun onDestroy() {
        dotroidPopup?.dismiss()
        scope.cancel()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                try { updateWorkspaceState() } catch (e: Exception) {
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
        val allowedAppsFromPolicy = withContext(Dispatchers.IO) {
            database.appPolicyDao().getAllPolicies()
        }
        val localAllowedApps = withContext(Dispatchers.IO) {
            database.allowedAppDao().getAll()
        }
        val pocketApps = withContext(Dispatchers.IO) {
            database.appPocketDao().getAllApps()
        }

        val allowedSet = mutableSetOf<String>()
        allowedAppsFromPolicy
            .filter { it.mode.uppercase() == "REQUIRED" || (it.mode.uppercase() == "ALLOWED" && it.pinned) }
            .forEach { allowedSet.add(it.packageName.trim().lowercase()) }
        localAllowedApps.forEach { allowedSet.add(it.packageName.trim().lowercase()) }
        pocketApps.filter { it.status == "INSTALLED" }
            .forEach { allowedSet.add(it.packageName.trim().lowercase()) }

        allowedPackages = allowedSet

        val foregroundPkg = getForegroundPackage()
        if (foregroundPkg != null && allowedPackages.contains(foregroundPkg.lowercase())) {
            lastActiveAppPackage = foregroundPkg
            repository.addRecentApp(foregroundPkg)
        }

        val activePkg = if (foregroundPkg != null && allowedPackages.contains(foregroundPkg.lowercase())) {
            foregroundPkg
        } else {
            lastActiveAppPackage ?: repository.getRecentApps()
                .lastOrNull { allowedPackages.contains(it.lowercase()) }
        }

        val runningPackages = getRunningPackages()
        runningPackages.forEach { pkg ->
            if (allowedPackages.contains(pkg.lowercase())) repository.addRecentApp(pkg)
        }

        val removedApps = repository.getRemovedApps()
        val pinned = repository.getPinnedApps()
            .filter { allowedPackages.contains(it.lowercase()) && !removedApps.contains(it) }
        val recents = repository.getRecentApps()
            .filter { allowedPackages.contains(it.lowercase()) && !pinned.contains(it) && !removedApps.contains(it) }

        val allPkgs = (pinned + recents).distinct().toMutableList()
        if (foregroundPkg != null && allowedPackages.contains(foregroundPkg.lowercase()) &&
            !allPkgs.contains(foregroundPkg) && !removedApps.contains(foregroundPkg)
        ) allPkgs.add(foregroundPkg)

        val items = withContext(Dispatchers.IO) {
            allPkgs.map { pkg ->
                var appName = pkg
                var icon: android.graphics.drawable.Drawable? = null
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    appName = pm.getApplicationLabel(info).toString()
                    icon = pm.getApplicationIcon(info)
                } catch (_: Exception) {}
                WorkspaceItem(
                    packageName = pkg,
                    name = appName,
                    icon = icon,
                    isRunning = pkg == activePkg,
                    isPinned = pinned.contains(pkg)
                )
            }
        }
        workspaceItems = items

        val visible = if (items.size <= 5) {
            items.toMutableList()
        } else {
            items.take(4).toMutableList<WorkspaceItem>().also {
                it.add(
                    WorkspaceItem(
                        packageName = "overflow", name = "More", icon = null,
                        isRunning = false, isPinned = false,
                        isOverflow = true, overflowCount = items.size - 4
                    )
                )
            }
        }
        withContext(Dispatchers.Main) { adapter.submitList(visible) }
    }

    private fun getForegroundPackage(): String? {
        return try {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val top = am.getRunningTasks(1).firstOrNull()?.topActivity?.packageName
            if (top != null && !top.contains("iips.launcher", ignoreCase = true)) top else null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting foreground", e); null
        }
    }

    fun addRecentApp(packageName: String) {
        scope.launch {
            if (allowedPackages.isEmpty()) updateWorkspaceState()
            if (allowedPackages.contains(packageName.lowercase())) {
                repository.addRecentApp(packageName)
                updateWorkspaceState()
            }
        }
    }

    private fun getRunningPackages(): Set<String> {
        if (usageStatsManager == null) return emptySet()
        val end = System.currentTimeMillis()
        val start = end - 1000 * 60 * 2
        return usageStatsManager
            .queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end)
            ?.filter { it.lastTimeUsed > start }
            ?.map { it.packageName }
            ?.toSet() ?: emptySet()
    }

    private fun launchOrSwitchToApp(packageName: String) {
        try {
            val intent = pm.getLaunchIntentForPackage(packageName) ?: run {
                Toast.makeText(activity, "App cannot be launched", Toast.LENGTH_SHORT).show()
                return
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
            repository.addRecentApp(packageName)
            scope.launch { updateWorkspaceState() }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app: $packageName", e)
        }
    }

    private fun showAppContextMenu(item: WorkspaceItem, view: View) {
        val popup = PopupMenu(activity, view)
        popup.menu.add(0, 1, 0, if (item.isPinned) "Unpin from Workspace" else "Pin to Workspace")
        popup.menu.add(0, 2, 1, "Restart Workspace App (Admin Only)")
        popup.menu.add(0, 3, 2, "Remove from Taskbar")

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> {
                    if (item.isPinned) repository.unpinApp(item.packageName)
                    else repository.pinApp(item.packageName)
                    scope.launch { updateWorkspaceState() }
                    true
                }
                2 -> {
                    AdminPasswordDialogFragment { success ->
                        if (success) showRestartConfirmationDialog(item)
                    }.show(activity.supportFragmentManager, "admin_auth_restart")
                    true
                }
                3 -> {
                    removeFromTaskbar(item)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun removeFromTaskbar(item: WorkspaceItem) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Remove from Taskbar")
            .setMessage("Remove ${item.name} from the taskbar? It will re-appear when launched again.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                repository.removeApp(item.packageName)
                if (item.isPinned) repository.unpinApp(item.packageName)
                scope.launch { updateWorkspaceState() }
                Toast.makeText(activity, "${item.name} removed", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showRestartConfirmationDialog(item: WorkspaceItem) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Restart ${item.name}?")
            .setMessage("Unsaved work may be lost.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restart") { _, _ -> restartApp(item.packageName) }
            .show()
    }

    private fun restartApp(packageName: String) {
        try {
            val intent = pm.getLaunchIntentForPackage(packageName) ?: run {
                Toast.makeText(activity, "Failed to locate app launch intent", Toast.LENGTH_SHORT).show()
                return
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting app: $packageName", e)
        }
    }

    private fun showOverflowMenu(view: View) {
        val popup = PopupMenu(activity, view)
        val overflowItems = workspaceItems.drop(4)
        overflowItems.forEachIndexed { index, item -> popup.menu.add(0, index, 0, item.name) }
        popup.setOnMenuItemClickListener { menuItem ->
            overflowItems.getOrNull(menuItem.itemId)?.let {
                launchOrSwitchToApp(it.packageName); true
            } ?: false
        }
        popup.show()
    }

    private fun observeAlertsAndGuard() {
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
    }
}
