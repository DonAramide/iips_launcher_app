package com.iips.launcher.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.iips.launcher.R
import com.iips.launcher.data.AllowedApp
import com.iips.launcher.data.AppDatabase
import com.iips.launcher.data.AppInfo
import com.iips.launcher.databinding.ActivityAppSelectionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSelectionBinding
    private lateinit var database: AppDatabase
    private lateinit var adapter: AppSelectionAdapter
    private var allApps: List<AppInfo> = emptyList()
    private var filteredApps: List<AppInfo> = emptyList()
    private val selectedPackages = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)
        
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        setupRecyclerView()
        loadAllApps()
        loadSelectedApps()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_app_selection, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                filterApps(newText ?: "")
                return true
            }
        })

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_save -> {
                saveSelectedApps()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerView() {
        adapter = AppSelectionAdapter(
            onAppSelected = { appInfo, isSelected ->
                // Use lowercase for consistent matching
                val packageNameKey = appInfo.packageName.trim().lowercase()
                if (isSelected) {
                    selectedPackages.add(packageNameKey)
                } else {
                    selectedPackages.remove(packageNameKey)
                }
                updateSelectedCount()
            }
        )

        binding.appList.apply {
            layoutManager = LinearLayoutManager(this@AppSelectionActivity)
            adapter = this@AppSelectionActivity.adapter
        }
    }
    
    private fun updateSelectedCount() {
        val count = selectedPackages.size
        supportActionBar?.title = if (count > 0) {
            "Select Apps ($count selected)"
        } else {
            getString(R.string.select_apps)
        }
    }

    private fun loadAllApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val packageManager = packageManager
                val intent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }

                val resolvedApps = packageManager.queryIntentActivities(intent, 0)
                resolvedApps.map { resolveInfo ->
                    AppInfo.fromApplicationInfo(
                        resolveInfo.activityInfo.applicationInfo,
                        packageManager
                    )
                }.sortedBy { it.name }
            }
            allApps = apps
            filteredApps = apps
            adapter.submitList(filteredApps)
        }
    }

    private fun loadSelectedApps() {
        lifecycleScope.launch {
            val allowedApps = withContext(Dispatchers.IO) {
                database.allowedAppDao().getAll()
            }
            selectedPackages.clear()
            // Store package names in lowercase for consistent matching
            selectedPackages.addAll(allowedApps.map { it.packageName.trim().lowercase() })
            adapter.setSelectedPackages(selectedPackages)
            updateSelectedCount()
        }
    }

    private fun filterApps(query: String) {
        filteredApps = if (query.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.name.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
            }
        }
        adapter.submitList(filteredApps)
    }

    private fun saveSelectedApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Get current allowed apps
            val currentAllowed = database.allowedAppDao().getAll()
            val currentPackages = currentAllowed.map { it.packageName }.toSet()

            // Remove apps that are no longer selected
            val toRemove = currentPackages - selectedPackages
            toRemove.forEach { packageName ->
                database.allowedAppDao().deleteByPackageName(packageName)
            }

            // Add newly selected apps
            val toAdd = selectedPackages - currentPackages
            toAdd.forEach { packageName ->
                val appInfo = allApps.find { 
                    it.packageName.trim().lowercase() == packageName.trim().lowercase() 
                }
                if (appInfo != null) {
                    // Store with original case
                    database.allowedAppDao().insert(
                        AllowedApp(
                            packageName = appInfo.packageName.trim(),
                            appName = appInfo.name.trim()
                        )
                    )
                }
            }

            withContext(Dispatchers.Main) {
                // Refresh the allowed apps cache in LauncherApplication
                val app = application as? com.iips.launcher.LauncherApplication
                app?.refreshAllowedApps()
                
                Toast.makeText(this@AppSelectionActivity, R.string.app_added, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}

