package com.iips.launcher.pocket.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.iips.launcher.R
import com.iips.launcher.data.AppDatabase
import com.iips.launcher.databinding.ActivityAppPocketBinding
import com.iips.launcher.install.PackageInstallManager
import com.iips.launcher.pocket.AppPocketApprovalManager
import com.iips.launcher.pocket.data.AppPocketEntity
import com.iips.launcher.install.SilentInstallReceiver
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class AppPocketActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPocketBinding

    @Inject
    lateinit var database: AppDatabase

    @Inject
    lateinit var approvalManager: AppPocketApprovalManager

    @Inject
    lateinit var packageInstallManager: PackageInstallManager

    @Inject
    lateinit var remoteCommandEngine: com.iips.launcher.convergence.RemoteCommandExecutionEngine

    private lateinit var adapter: AppPocketAdapter
    private var allApps = emptyList<AppPocketEntity>()
    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPocketBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeApps()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnSyncCatalog.setOnClickListener {
            triggerCatalogSync()
        }

        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Required Apps"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Company Store"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Installed Apps"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Approval Queue"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: 0
                filterAndDisplayApps()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        adapter = AppPocketAdapter(
            onPrimaryAction = { app -> handlePrimaryAction(app) },
            onSecondaryAction = { app -> handleSecondaryAction(app) }
        )
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter
    }

    private fun observeApps() {
        val pocketDao = database.appPocketDao()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                pocketDao.getAllAppsFlow().collect { apps ->
                    allApps = apps
                    updateMetrics()
                    filterAndDisplayApps()
                }
            }
        }
    }

    private fun updateMetrics() {
        val missingCount = allApps.count { it.isRequired && it.status != "INSTALLED" }
        val updatesCount = allApps.count { it.status == "UPDATE_AVAILABLE" }
        val pendingCount = allApps.count { it.status == "AWAITING_APPROVAL" }

        binding.txtMissingCount.text = missingCount.toString()
        binding.txtUpdatesCount.text = updatesCount.toString()
        binding.txtPendingCount.text = pendingCount.toString()
    }

    private fun filterAndDisplayApps() {
        val filtered = when (currentTab) {
            0 -> allApps.filter { it.isRequired }
            1 -> allApps.filter { it.status != "INSTALLED" && !it.isRequired }
            2 -> allApps.filter { it.status == "INSTALLED" }
            3 -> allApps.filter { it.status == "AWAITING_APPROVAL" }
            else -> allApps
        }

        adapter.submitList(filtered)

        if (filtered.isEmpty()) {
            binding.pocketEmptyState.visibility = View.VISIBLE
            binding.rvApps.visibility = View.GONE
        } else {
            binding.pocketEmptyState.visibility = View.GONE
            binding.rvApps.visibility = View.VISIBLE
        }
    }

    private fun handlePrimaryAction(app: AppPocketEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (app.downloadStatus == "FAILED") {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AppPocketActivity, "Resuming download...", Toast.LENGTH_SHORT).show()
                }
                remoteCommandEngine.resumeDownload(app.packageName)
                return@launch
            }
            when (app.status) {
                "PENDING" -> {
                    approvalManager.requestApproval(app.packageName, "operator_user_1")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AppPocketActivity, "Submitted to Approval Queue", Toast.LENGTH_SHORT).show()
                    }
                }
                "AWAITING_APPROVAL" -> {
                    approvalManager.approveApp(app.packageName, "manager_admin_1")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AppPocketActivity, "Approved App", Toast.LENGTH_SHORT).show()
                    }
                }
                "APPROVED", "REMOVED" -> {
                    val path = app.apkPath
                    if (path != null) {
                        val file = File(path)
                        if (file.exists()) {
                            packageInstallManager.installPackage(file, app.packageName, null)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@AppPocketActivity, "Initiating Silent Installation...", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@AppPocketActivity, "APK file not found: $path", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@AppPocketActivity, "APK path is null", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                "REJECTED" -> {
                    approvalManager.requestApproval(app.packageName, "operator_user_1")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AppPocketActivity, "Retried approval request", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun handleSecondaryAction(app: AppPocketEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            when (app.status) {
                "AWAITING_APPROVAL" -> {
                    approvalManager.rejectApp(app.packageName, "manager_admin_1")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AppPocketActivity, "Rejected App", Toast.LENGTH_SHORT).show()
                    }
                }
                "INSTALLED" -> {
                    if (!app.isRequired) {
                        performUninstall(app.packageName)
                    }
                }
            }
        }
    }

    private fun performUninstall(packageName: String) {
        try {
            val packageInstaller = packageManager.packageInstaller
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                102,
                Intent(this, SilentInstallReceiver::class.java),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )
            packageInstaller.uninstall(packageName, pendingIntent.intentSender)
            runOnUiThread {
                Toast.makeText(this, "Uninstalling package: $packageName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Uninstall failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun triggerCatalogSync() {
        Toast.makeText(this, "Triggering Quasar catalog sync...", Toast.LENGTH_SHORT).show()
        com.iips.launcher.pocket.sync.AppPocketSyncWorker.enqueue(this)
    }
}
