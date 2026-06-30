package com.iips.launcher.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.iips.launcher.auth.GuardDeviceSyncRepository
import com.iips.launcher.data.PairedDeviceDao
import com.iips.launcher.data.PairedDeviceEntity
import com.iips.launcher.databinding.ActivityGuardMainBinding
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GuardMainActivity : AppCompatActivity() {

    @Inject
    lateinit var syncRepository: GuardDeviceSyncRepository

    @Inject
    lateinit var deviceDao: PairedDeviceDao

    private lateinit var binding: ActivityGuardMainBinding
    private lateinit var adapter: PairedDeviceAdapter
    private var allDevices: List<PairedDeviceEntity> = emptyList()
    private var currentFilter: String = "ALL" // ALL, ONLINE, OFFLINE, LOCKED, PENDING, ALERTS

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            triggerSync()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuardMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup Toolbar
        setSupportActionBar(binding.toolbar)

        // Setup Recycler View
        binding.deviceListRecycler.layoutManager = LinearLayoutManager(this)
        adapter = PairedDeviceAdapter(emptyList()) { device ->
            val intent = Intent(this, GuardDeviceDetailActivity::class.java).apply {
                putExtra(GuardDeviceDetailActivity.EXTRA_DEVICE_ID, device.deviceId)
            }
            startActivity(intent)
        }
        binding.deviceListRecycler.adapter = adapter

        // Setup FAB and Empty State pairing trigger
        binding.fabPair.setOnClickListener {
            scanLauncher.launch(Intent(this, GuardScannerActivity::class.java))
        }
        binding.btnPairDevice.setOnClickListener {
            scanLauncher.launch(Intent(this, GuardScannerActivity::class.java))
        }

        // Setup Swipe Refresh
        binding.swipeRefresh.setOnRefreshListener {
            triggerSync()
        }

        // Setup Filter Click Listeners
        setupFilters()

        // Initial Load from cache and Sync
        loadLocalData()
        triggerSync()
    }

    private fun setupFilters() {
        binding.cardTotal.setOnClickListener { setFilter("ALL") }
        binding.cardOnline.setOnClickListener { setFilter("ONLINE") }
        binding.cardOffline.setOnClickListener { setFilter("OFFLINE") }
        binding.cardLocked.setOnClickListener { setFilter("LOCKED") }
        binding.cardPending.setOnClickListener { setFilter("PENDING") }
        binding.cardAlerts.setOnClickListener { setFilter("ALERTS") }
    }

    private fun setFilter(filter: String) {
        currentFilter = filter
        applyFilter()
        Toast.makeText(this, "Filtering by: $filter", Toast.LENGTH_SHORT).show()
    }

    private fun applyFilter() {
        val filtered = when (currentFilter) {
            "ONLINE" -> allDevices.filter { it.connectivityStatus == "ONLINE" }
            "OFFLINE" -> allDevices.filter { it.connectivityStatus == "OFFLINE" }
            "LOCKED" -> allDevices.filter { it.securityStatus == "LOCKED" }
            "PENDING" -> allDevices.filter { it.securityStatus == "PENDING" }
            "ALERTS" -> allDevices.filter { it.unreadAlertCount > 0 }
            else -> allDevices
        }

        adapter.updateData(filtered)

        if (filtered.isEmpty()) {
            binding.deviceListRecycler.visibility = View.GONE
            if (allDevices.isEmpty()) {
                binding.emptyStateView.visibility = View.VISIBLE
            } else {
                binding.emptyStateView.visibility = View.GONE
            }
        } else {
            binding.deviceListRecycler.visibility = View.VISIBLE
            binding.emptyStateView.visibility = View.GONE
        }
    }

    private fun loadLocalData() {
        lifecycleScope.launch {
            try {
                deviceDao.getAllDevicesFlow().collect { devices ->
                    allDevices = devices
                    updateCountersFromList(allDevices)
                    applyFilter()
                }
            } catch (e: Exception) {
                // Ignore cache read failures
            }
        }
    }

    private fun triggerSync() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            val syncResult = syncRepository.syncDevices()
            val summaryResult = syncRepository.getDeviceSummary()

            binding.swipeRefresh.isRefreshing = false

            if (syncResult.isFailure) {
                Toast.makeText(
                    this@GuardMainActivity,
                    "Sync failed: ${syncResult.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG
                ).show()
            }

            if (summaryResult.isSuccess) {
                val summary = summaryResult.getOrThrow()
                binding.tvCountTotal.text = summary.totalDevices.toString()
                binding.tvCountOnline.text = summary.onlineDevices.toString()
                binding.tvCountOffline.text = summary.offlineDevices.toString()
                binding.tvCountLocked.text = summary.lockedDevices.toString()
                binding.tvCountPending.text = summary.pendingDevices.toString()
                binding.tvCountAlerts.text = summary.totalAlerts.toString()
            }
        }
    }

    private fun updateCountersFromList(list: List<PairedDeviceEntity>) {
        binding.tvCountTotal.text = list.size.toString()
        binding.tvCountOnline.text = list.count { it.connectivityStatus == "ONLINE" }.toString()
        binding.tvCountOffline.text = list.count { it.connectivityStatus == "OFFLINE" }.toString()
        binding.tvCountLocked.text = list.count { it.securityStatus == "LOCKED" }.toString()
        binding.tvCountPending.text = list.count { it.securityStatus == "PENDING" }.toString()
        binding.tvCountAlerts.text = list.sumOf { it.unreadAlertCount }.toString()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(com.iips.launcher.R.menu.menu_guard_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            com.iips.launcher.R.id.action_notifications -> {
                Toast.makeText(this, "Notifications clicked", Toast.LENGTH_SHORT).show()
                true
            }
            com.iips.launcher.R.id.action_logout -> {
                performLogout()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun performLogout() {
        SecurePreferences.setGuardAuthToken(this, "")
        SecurePreferences.setGuardRefreshToken(this, "")
        Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, GuardLoginActivity::class.java))
        finish()
    }
}
