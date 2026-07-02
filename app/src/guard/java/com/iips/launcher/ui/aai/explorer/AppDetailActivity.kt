package com.iips.launcher.ui.aai.explorer

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.iips.launcher.aai.viewmodel.AaiAppExplorerViewModel
import com.iips.launcher.aai.viewmodel.UiState
import com.iips.launcher.databinding.ActivityAppDetailBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AppDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_DEVICE_ID = "extra_device_id"
    }

    private lateinit var binding: ActivityAppDetailBinding
    private val viewModel: AaiAppExplorerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return finish()
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = packageName

        viewModel.loadAppDetail(packageName, deviceId)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.appDetail.collect { state ->
                    binding.progressBar.visibility = if (state is UiState.Loading) View.VISIBLE else View.GONE
                    binding.contentLayout.visibility = if (state is UiState.Success) View.VISIBLE else View.GONE
                    binding.tvError.visibility = if (state is UiState.Error) View.VISIBLE else View.GONE

                    if (state is UiState.Success) {
                        val detail = state.data
                        val app = detail.app
                        binding.tvAppName.text = app.appName
                        binding.tvPackageName.text = app.packageName
                        binding.tvCategory.text = app.category ?: "Unknown"
                        binding.tvRiskLevel.text = app.riskLevel ?: "—"
                        binding.tvTrustScore.text = app.trustScore?.let { "%.0f%%".format(it * 100) } ?: "—"
                        binding.tvVersion.text = "${app.versionName ?: "—"} (${app.versionCode ?: 0})"
                        binding.tvIsSystem.text = if (app.isSystemApp) "System App" else "User App"
                        binding.tvRunning.text = if (app.isRunning) "RUNNING" else "Idle"
                        binding.tvRecentSessions.text = "${detail.recentSessions.size} recent sessions"

                        val historyText = detail.versionHistory.joinToString("\n") { v ->
                            "${v.versionName} (${v.versionCode}) — installed ${v.installedAt.take(10)}"
                        }.ifBlank { "No version history" }
                        binding.tvVersionHistory.text = historyText
                    }

                    if (state is UiState.Error) binding.tvError.text = state.message
                }
            }
        }

        binding.btnRetry.setOnClickListener {
            viewModel.loadAppDetail(packageName, deviceId)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
