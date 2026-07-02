package com.iips.launcher.ui.aai

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.iips.launcher.R
import com.iips.launcher.aai.viewmodel.*
import com.iips.launcher.databinding.ActivityAaiConsoleBinding
import com.iips.launcher.ui.aai.dashboard.AaiDashboardFragment
import com.iips.launcher.ui.aai.explorer.AppExplorerFragment
import com.iips.launcher.ui.aai.session.SessionExplorerFragment
import com.iips.launcher.ui.aai.investigation.InvestigationFragment
import com.iips.launcher.ui.aai.compliance.ComplianceFragment
import com.iips.launcher.ui.aai.operations.OperationsFragment
import com.iips.launcher.ui.aai.filter.AaiFilterBottomSheet
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class GuardAaiConsoleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAaiConsoleBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAaiConsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupTabs()

        binding.fabFilter.setOnClickListener {
            AaiFilterBottomSheet().show(supportFragmentManager, "aai_filter")
        }
    }

    private fun setupTabs() {
        val pages: List<Pair<String, Fragment>> = listOf(
            "Dashboard" to AaiDashboardFragment(),
            "Apps" to AppExplorerFragment(),
            "Sessions" to SessionExplorerFragment(),
            "Investigate" to InvestigationFragment(),
            "Compliance" to ComplianceFragment(),
            "Operations" to OperationsFragment()
        )

        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = pages.size
            override fun createFragment(position: Int) = pages[position].second
        }

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = pages[position].first
        }.attach()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_aai_console, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_refresh -> {
                // Notify active fragment to refresh
                Toast.makeText(this, "Refreshing…", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
