package com.iips.launcher.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.iips.launcher.R
import com.iips.launcher.auth.GuardDeviceSyncRepository
import com.iips.launcher.data.PairedDeviceDao
import com.iips.launcher.data.PairedDeviceEntity
import com.iips.launcher.databinding.ActivityGuardDeviceDetailBinding
import com.iips.launcher.utils.RelativeTimeUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GuardDeviceDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"
    }

    @Inject
    lateinit var deviceDao: PairedDeviceDao

    @Inject
    lateinit var syncRepository: GuardDeviceSyncRepository

    private lateinit var binding: ActivityGuardDeviceDetailBinding
    private var deviceId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuardDeviceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        if (deviceId.isNullOrEmpty()) {
            Toast.makeText(this, "Device ID not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Setup Toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Load Cached Data first
        loadCachedDetails()

        // Sync fresh data from backend
        syncDetails()

        // Setup Actions
        setupActions()
    }

    private fun setupActions() {
        binding.btnLockDevice.setOnClickListener {
            updateDeviceStatus("LOCKED")
        }
        binding.btnUnlockDevice.setOnClickListener {
            updateDeviceStatus("NORMAL")
        }
    }

    private fun updateDeviceStatus(status: String) {
        if (deviceId.isNullOrEmpty()) return
        lifecycleScope.launch {
            binding.btnLockDevice.isEnabled = false
            binding.btnUnlockDevice.isEnabled = false
            
            val result = syncRepository.updateDeviceStatus(deviceId!!, status)
            if (result.isSuccess) {
                Toast.makeText(this@GuardDeviceDetailActivity, "Device status updated successfully", Toast.LENGTH_SHORT).show()
                syncDetails()
            } else {
                Toast.makeText(this@GuardDeviceDetailActivity, "Failed to update status", Toast.LENGTH_SHORT).show()
            }
            
            binding.btnLockDevice.isEnabled = true
            binding.btnUnlockDevice.isEnabled = true
        }
    }

    private fun loadCachedDetails() {
        lifecycleScope.launch {
            val device = deviceDao.getDeviceById(deviceId!!)
            if (device != null) {
                bindDeviceData(device)
            }
        }
    }

    private fun syncDetails() {
        lifecycleScope.launch {
            val result = syncRepository.syncDeviceDetails(deviceId!!)
            if (result.isSuccess) {
                bindDeviceData(result.getOrThrow())
            } else {
                Toast.makeText(
                    this@GuardDeviceDetailActivity,
                    "Sync details failed: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun bindDeviceData(device: PairedDeviceEntity) {
        binding.tvDetailDeviceName.text = device.deviceName
        binding.tvDetailMerchantBranch.text = "${device.merchantName} • ${device.branchName}"
        binding.tvDetailLastSeen.text = RelativeTimeUtils.formatRelativeTime(device.lastSeen)

        binding.tvDetailBattery.text = device.batteryLevel?.let { "$it%" } ?: "N/A"
        binding.tvDetailNetwork.text = device.networkStatus ?: "N/A"
        binding.tvDetailGuardStatus.text = device.guardStatus ?: "NORMAL"

        binding.tvDetailUnreadAlerts.text = if (device.unreadAlertCount == 1) "1 Alert" else "${device.unreadAlertCount} Alerts"

        // 1. Connectivity Badge
        binding.tvDetailConnectivityBadge.text = device.connectivityStatus
        val connColor = if (device.connectivityStatus == "ONLINE") {
            ContextCompat.getColor(this, R.color.success)
        } else {
            ContextCompat.getColor(this, R.color.text_secondary)
        }
        binding.tvDetailConnectivityBadge.backgroundTintList = ColorStateList.valueOf(connColor)

        // 2. Security Badge
        binding.tvDetailSecurityBadge.text = device.securityStatus
        val secColor = when (device.securityStatus) {
            "NORMAL" -> ContextCompat.getColor(this, R.color.success)
            "LOCKED" -> ContextCompat.getColor(this, R.color.error)
            "PENDING" -> ContextCompat.getColor(this, R.color.warning)
            "SUSPENDED" -> ContextCompat.getColor(this, R.color.accent)
            else -> ContextCompat.getColor(this, R.color.primary)
        }
        binding.tvDetailSecurityBadge.backgroundTintList = ColorStateList.valueOf(secColor)

        // 3. Health Badge
        binding.tvDetailHealthBadge.text = device.deviceHealthStatus
        val healthColor = when (device.deviceHealthStatus) {
            "HEALTHY" -> ContextCompat.getColor(this, R.color.success)
            "WARNING" -> ContextCompat.getColor(this, R.color.warning)
            "CRITICAL" -> ContextCompat.getColor(this, R.color.error)
            else -> ContextCompat.getColor(this, R.color.success)
        }
        binding.tvDetailHealthBadge.backgroundTintList = ColorStateList.valueOf(healthColor)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
