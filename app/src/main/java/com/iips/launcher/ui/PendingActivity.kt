package com.iips.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iips.launcher.R
import com.iips.launcher.config.ConfigManager
import com.iips.launcher.utils.SecurePreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PendingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pending)
        
        // Start polling for status
        startPolling()
        
        setupDebugBypass()
        setupRetryButton()
    }

    private fun setupRetryButton() {
        findViewById<android.widget.Button>(R.id.btn_retry_registration).setOnClickListener {
            // Clear registration state to allow re-onboarding
            SecurePreferences.setDeviceState(this, SecurePreferences.STATE_ONBOARDING)
            
            // Navigate back to Onboarding
            val intent = Intent(this, OnboardingActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finish()
        }
    }

    private fun setupDebugBypass() {
        // Find the root view or a main text view to attach the long press
        findViewById<android.view.View>(android.R.id.content).setOnLongClickListener {
            android.util.Log.w("PendingActivity", "DEBUG BYPASS: Manually activating device")
            Toast.makeText(this, "Debug Bypass: Activating...", Toast.LENGTH_SHORT).show()
            SecurePreferences.setDeviceState(this, SecurePreferences.STATE_ACTIVE)
            navigateToLauncher()
            true
        }
    }

    private fun startPolling() {
        lifecycleScope.launch {
            while (isActive) {
                // Poll every 10 seconds for faster development feedback
                delay(10000)
                
                // Assuming ConfigManager exposes checkActivationStatus or we can implement it there
                val deviceId = SecurePreferences.getDeviceId(this@PendingActivity)
                if (deviceId != null) {
                    try {
                        // Check status
                        val isApproved = ConfigManager.checkActivationStatus(this@PendingActivity, deviceId)
                        if (isApproved) {
                            SecurePreferences.setDeviceState(this@PendingActivity, SecurePreferences.STATE_ACTIVE)
                            navigateToLauncher()
                            break
                        }
                    } catch (e: Exception) {
                        // Ignore and retry next tick
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
        // Do nothing to prevent escaping
    }
}
