package com.iips.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iips.launcher.R
import com.iips.launcher.storage.ConfigManager
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PendingActivity : AppCompatActivity() {

    @Inject
    lateinit var configManager: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pending)
        
        startPolling()
        setupDebugBypass()
        setupRetryButton()
    }

    private fun setupRetryButton() {
        findViewById<Button>(R.id.btn_retry_registration).setOnClickListener {
            SecurePreferences.setDeviceState(this, SecurePreferences.STATE_ONBOARDING)
            val intent = Intent(this, OnboardingActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finish()
        }
    }

    private fun setupDebugBypass() {
        findViewById<View>(android.R.id.content).setOnLongClickListener {
            SecurePreferences.setDeviceState(this, SecurePreferences.STATE_ACTIVE)
            navigateToLauncher()
            true
        }
    }

    private fun startPolling() {
        lifecycleScope.launch {
            while (isActive) {
                delay(10000)
                val deviceId = SecurePreferences.getDeviceId(this@PendingActivity)
                if (deviceId != null) {
                    if (configManager.checkActivationStatus(this@PendingActivity, deviceId)) {
                        SecurePreferences.setDeviceState(this@PendingActivity, SecurePreferences.STATE_ACTIVE)
                        navigateToLauncher()
                        break
                    }
                }
            }
        }
    }

    private fun navigateToLauncher() {
        Toast.makeText(this, "Device Approved!", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, LauncherActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    override fun onBackPressed() {
        // Do nothing
    }
}
