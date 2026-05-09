package com.iips.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.iips.launcher.R
import com.iips.launcher.data.AppDatabase
import com.iips.launcher.databinding.ActivityAdminBinding
import com.iips.launcher.policy.DeviceController
import com.iips.launcher.storage.SecurePreferences
import com.iips.launcher.apps.SystemAppUtils
import com.iips.launcher.storage.ConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private lateinit var database: AppDatabase
    private lateinit var usageLogAdapter: AppUsageLogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)
        usageLogAdapter = AppUsageLogAdapter()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupViews()
        setupUsageLogsRecyclerView()
        updateLockdownStatus()
        updateMdmStatus()
        updateGeofenceStatus()
        loadUsageLogs()
    }

    private fun updateMdmStatus() {
        val deviceId = SecurePreferences.getDeviceId(this)
        if (deviceId != null && deviceId.isNotEmpty()) {
            binding.deviceIdText.text = getString(R.string.device_id_label, deviceId)
            binding.forceHeartbeatButton.isEnabled = true
        } else {
            binding.deviceIdText.text = getString(R.string.not_registered)
            binding.forceHeartbeatButton.isEnabled = false
        }

        // Update System Status Badges
        val status = SystemAppUtils.getAppStatus(this)
        
        binding.systemStatusBadge.text = if (status.isInstalledToSystem) "System App: YES" else "System App: NO"
        binding.systemStatusBadge.setBackgroundColor(
            if (status.isInstalledToSystem) android.graphics.Color.parseColor("#4CAF50") 
            else android.graphics.Color.parseColor("#757575")
        )

        binding.deviceOwnerBadge.text = if (status.isDeviceOwner) "Device Owner: YES" else "Device Owner: NO"
        binding.deviceOwnerBadge.setBackgroundColor(
            if (status.isDeviceOwner) android.graphics.Color.parseColor("#2196F3") 
            else android.graphics.Color.parseColor("#757575")
        )
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_admin, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_settings -> {
                showChangePasswordDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onStart() {
        super.onStart()
        android.util.Log.d("AdminActivity", "onStart - AdminActivity is starting")
        // Disable lock task immediately when activity starts
        DeviceController.stopLockTask(this)
        
        // Ensure AdminActivity stays on top - bring to front
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) {
                try {
                    window.decorView.requestFocus()
                    // Force AdminActivity to stay on top
                    val intent = Intent(this, AdminActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.w("AdminActivity", "Could not bring to front: ${e.message}")
                }
            }
        }, 100)
    }
    
    override fun onResume() {
        super.onResume()
        android.util.Log.d("AdminActivity", "onResume - AdminActivity is resuming")
        
        // Temporarily disable lock task mode for admin panel - do this aggressively
        DeviceController.stopLockTask(this)
        DeviceController.disableImmersiveMode(this)
        
        // Ensure AdminActivity stays on top and doesn't get intercepted
        // Bring AdminActivity to front explicitly
        window.decorView.post {
            if (!isFinishing && !isDestroyed) {
                window.decorView.requestFocus()
                android.util.Log.d("AdminActivity", "AdminActivity window has focus")
                
                // Also post again to ensure we stay on top
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        DeviceController.stopLockTask(this)
                        window.decorView.requestFocus()
                    }
                }, 200)
            }
        }
        
        // Reload usage logs when resuming
        loadUsageLogs()
        updateGeofenceStatus()
    }
    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        android.util.Log.d("AdminActivity", "onWindowFocusChanged - hasFocus: $hasFocus")
        if (hasFocus) {
            // Ensure AdminActivity maintains focus and lock task is disabled
            DeviceController.stopLockTask(this)
            window.decorView.requestFocus()
        }
    }

    override fun onPause() {
        super.onPause()
        // Re-enable lock task when leaving admin panel
        if (SecurePreferences.isLockdownEnabled(this)) {
            DeviceController.startLockTask(this)
        }
        if (SecurePreferences.isImmersiveModeEnabled(this)) {
            DeviceController.enableImmersiveMode(this)
        }
    }

    private fun setupViews() {
        binding.addAppButton.setOnClickListener {
            val intent = Intent(this, AppSelectionActivity::class.java)
            startActivity(intent)
        }

        // Store listener for restoration after programmatic changes
        lockdownToggleListener = { isChecked ->
            SecurePreferences.setLockdownEnabled(this, isChecked)

            if (isChecked) {
                Toast.makeText(this, R.string.lockdown_enabled, Toast.LENGTH_SHORT).show()
                DeviceController.startLockTask(this)
            } else {
                Toast.makeText(this, R.string.lockdown_disabled, Toast.LENGTH_SHORT).show()
                DeviceController.stopLockTask(this)
            }
        }
        
        binding.lockdownToggle.setOnCheckedChangeListener { _, isChecked ->
            lockdownToggleListener?.invoke(isChecked)
        }

        binding.immersiveToggle.setOnCheckedChangeListener { _, isChecked ->
            SecurePreferences.setImmersiveModeEnabled(this, isChecked)
            if (isChecked) {
                DeviceController.enableImmersiveMode(this)
            } else {
                DeviceController.disableImmersiveMode(this)
            }
        }

        // Store factory reset protection listener
        factoryResetProtectionListener = { isChecked ->
            SecurePreferences.setFactoryResetProtectionEnabled(this, isChecked)
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                if (isChecked) {
                    DeviceController.enableFactoryResetProtection(this)
                    Toast.makeText(this, R.string.factory_reset_protection_enabled, Toast.LENGTH_LONG).show()
                } else {
                    DeviceController.disableFactoryResetProtection(this)
                    Toast.makeText(this, R.string.factory_reset_protection_disabled, Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Factory reset protection requires Android 5.1 or higher", Toast.LENGTH_SHORT).show()
            }
        }
        
        binding.factoryResetProtectionToggle.setOnCheckedChangeListener { _, isChecked ->
            factoryResetProtectionListener?.invoke(isChecked)
        }

        // Config Server Setup
        binding.configUrlEditText.setText(SecurePreferences.getConfigUrl(this))
        binding.syncButton.setOnClickListener {
            val url = binding.configUrlEditText.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter a valid URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            SecurePreferences.setConfigUrl(this, url)
            
            lifecycleScope.launch {
                binding.syncButton.isEnabled = false
                binding.syncButton.text = getString(R.string.syncing)
                
                try {
                    // Trigger immediate sync via WorkManager
                    val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.iips.launcher.workers.PolicySyncWorker>().build()
                    androidx.work.WorkManager.getInstance(this@AdminActivity).enqueue(workRequest)
                    Toast.makeText(this@AdminActivity, R.string.sync_success, Toast.LENGTH_SHORT).show()
                    // Refresh logs and status after sync
                    loadUsageLogs()
                    updateLockdownStatus()
                    updateMdmStatus() // Also update MDM status as sync might affect it
                } catch (e: Exception) {
                    Toast.makeText(this@AdminActivity, getString(R.string.sync_failed, e.message), Toast.LENGTH_LONG).show()
                } finally {
                    binding.syncButton.isEnabled = true
                    binding.syncButton.text = getString(R.string.sync_config)
                }
            }
        }

        // MDM Management Setup
        binding.forceHeartbeatButton.setOnClickListener {
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.iips.launcher.workers.TelemetryWorker>().build()
            androidx.work.WorkManager.getInstance(this).enqueue(workRequest)
            Toast.makeText(this, R.string.heartbeat_triggered, Toast.LENGTH_SHORT).show()
        }

        binding.resetMdmButton.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.reset_mdm)
                .setMessage("Are you sure you want to reset MDM registration? This will clear the device token.")
                .setPositiveButton(R.string.confirm) { _, _ ->
                    SecurePreferences.setDeviceId(this, "")
                    SecurePreferences.setDeviceToken(this, "")
                    updateMdmStatus()
                    Toast.makeText(this, R.string.mdm_reset_success, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.systemResetButton.setOnClickListener {
            showSystemResetAuthDialog()
        }

        // Persistence Lock Setup
        binding.persistenceLockSwitch.isChecked = SecurePreferences.isFactoryResetProtectionEnabled(this)
        binding.persistenceLockSwitch.setOnCheckedChangeListener { _, isChecked ->
            SecurePreferences.setFactoryResetProtectionEnabled(this, isChecked)
            if (isChecked) {
                DeviceController.enableFactoryResetProtection(this)
                DeviceController.enableComprehensiveSecurity(this)
                Toast.makeText(this, R.string.persistence_enabled, Toast.LENGTH_SHORT).show()
            } else {
                DeviceController.disableFactoryResetProtection(this)
                // Note: enableComprehensiveSecurity restrictions are harder to undo individually
                // but DISALLOW_FACTORY_RESET is the main one for this toggle
                Toast.makeText(this, R.string.persistence_disabled, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnGeofenceEnrollment.setOnClickListener {
            val intent = Intent(this, GeofenceEnrollmentActivity::class.java)
            startActivity(intent)
        }
    }

    private fun showSystemResetAuthDialog() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Enter Super Admin Password"
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("System Reset Authentication")
            .setMessage("This action will WIPE ALL DATA and reset the launcher. Please enter the dynamic super admin password (yyyy/MM/DD/HH/mm-iips@admin123) to continue.")
            .setView(input)
            .setPositiveButton("WIPE EVERYTHING") { _, _ ->
                val password = input.text.toString()
                if (verifySuperAdminPassword(password)) {
                    performSystemReset()
                } else {
                    Toast.makeText(this, "Invalid Super Admin Password", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun verifySuperAdminPassword(input: String): Boolean {
        val sdf = java.text.SimpleDateFormat("yyyy/MM/dd/HH/mm", java.util.Locale.US)
        val currentTime = sdf.format(java.util.Date())
        val expected = "$currentTime-iips@admin123"
        
        android.util.Log.d("AdminActivity", "Super Admin Auth - Expected: $expected, Received: $input")
        
        // Allow a 1-minute grace period (current or previous minute)
        if (input == expected) return true
        
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.MINUTE, -1)
        val previousTime = sdf.format(calendar.time)
        val expectedPrevious = "$previousTime-iips@admin123"
        
        return input == expectedPrevious
    }

    private fun performSystemReset() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Cancel all WorkManager tasks
                androidx.work.WorkManager.getInstance(this@AdminActivity).cancelAllWork()
                
                // 2. Clear Database
                database.clearAllTables()
                
                // 3. Clear Preferences
                SecurePreferences.clearAll(this@AdminActivity)
                
                // 4. Disable Lockdown and Immersive mode
                withContext(Dispatchers.Main) {
                    DeviceController.stopLockTask(this@AdminActivity)
                    DeviceController.disableImmersiveMode(this@AdminActivity)
                    
                    Toast.makeText(this@AdminActivity, "System Reset Successful. Restarting...", Toast.LENGTH_LONG).show()
                    
                    // 5. Restart Application to Onboarding
                    val intent = Intent(this@AdminActivity, OnboardingActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("AdminActivity", "System Reset Failed", e)
                    Toast.makeText(this@AdminActivity, "Reset failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private var lockdownToggleListener: ((Boolean) -> Unit)? = null
    private var factoryResetProtectionListener: ((Boolean) -> Unit)? = null
    
    private fun updateLockdownStatus() {
        val isEnabled = SecurePreferences.isLockdownEnabled(this)
        // Temporarily disable listener to avoid triggering during update
        binding.lockdownToggle.setOnCheckedChangeListener(null)
        binding.lockdownToggle.isChecked = isEnabled
        // Re-enable listener after setting value (restore from setupViews)
        lockdownToggleListener?.let { listener ->
            binding.lockdownToggle.setOnCheckedChangeListener { _, isChecked ->
                listener(isChecked)
            }
        }

        val isImmersiveEnabled = SecurePreferences.isImmersiveModeEnabled(this)
        binding.immersiveToggle.isChecked = isImmersiveEnabled

        val isFactoryResetProtectionEnabled = SecurePreferences.isFactoryResetProtectionEnabled(this)
        // Temporarily disable listener to avoid triggering during update
        binding.factoryResetProtectionToggle.setOnCheckedChangeListener(null)
        binding.factoryResetProtectionToggle.isChecked = isFactoryResetProtectionEnabled
        // Re-enable listener after setting value
        factoryResetProtectionListener?.let { listener ->
            binding.factoryResetProtectionToggle.setOnCheckedChangeListener { _, isChecked ->
                listener(isChecked)
            }
        }
    }

    private fun updateGeofenceStatus() {
        val snapshot = SecurePreferences.getDevicePolicySnapshot(this)
        if (snapshot != null && snapshot.geofenceRules.isNotEmpty()) {
            binding.geofenceStatusBadge.text = getString(R.string.geofencing_enabled)
            binding.geofenceStatusBadge.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
            
            val zones = snapshot.geofenceRules
            val zonesText = zones.joinToString("\n") { zone ->
                "• ${zone.name}: ${zone.lat}, ${zone.lng} (r=${zone.radius_m}m)"
            }
            binding.geofenceZonesText.text = zonesText
            
            // Hide enrollment in enforcement mode
            binding.btnGeofenceEnrollment.visibility = View.GONE
        } else {
            binding.geofenceStatusBadge.text = getString(R.string.geofencing_disabled)
            binding.geofenceStatusBadge.setBackgroundColor(android.graphics.Color.parseColor("#757575"))
            binding.geofenceZonesText.text = "Geofencing is in ENROLLMENT mode"
            
            // Show enrollment in enrollment mode
            binding.btnGeofenceEnrollment.visibility = View.VISIBLE
        }
    }

    private fun showChangePasswordDialog() {
        val dialog = ChangePasswordDialogFragment {
            Toast.makeText(this, R.string.password_changed, Toast.LENGTH_SHORT).show()
        }
        dialog.show(supportFragmentManager, "change_password")
    }

    private fun setupUsageLogsRecyclerView() {
        binding.usageLogsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@AdminActivity)
            adapter = usageLogAdapter
        }
    }

    private fun loadUsageLogs() {
        lifecycleScope.launch {
            val logs = withContext(Dispatchers.IO) {
                database.appUsageLogDao().getAll()
            }

            if (logs.isEmpty()) {
                binding.usageLogsRecyclerView.visibility = View.GONE
                binding.emptyLogsText.visibility = View.VISIBLE
            } else {
                binding.usageLogsRecyclerView.visibility = View.VISIBLE
                binding.emptyLogsText.visibility = View.GONE
                usageLogAdapter.submitList(logs)
            }
        }
    }
}

