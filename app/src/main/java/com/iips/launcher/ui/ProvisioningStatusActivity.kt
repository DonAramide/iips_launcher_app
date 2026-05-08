package com.iips.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.iips.launcher.R
import com.iips.launcher.device.ProvisioningBootstrapService
import com.iips.launcher.utils.SecurePreferences

/**
 * Headless status activity shown during and after enterprise QR provisioning.
 *
 * Two states:
 *  - **Enrolling** (default): spinner + status text, no back press
 *  - **Error**: spinner hidden, error message + Retry button displayed
 *
 * This activity is started by [ProvisioningBootstrapService] only.
 * It is NOT part of the manual onboarding flow.
 */
class ProvisioningStatusActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
        const val ACTION_ENROLLMENT_SUCCESS = "com.iips.launcher.ENROLLMENT_SUCCESS"
    }

    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button

    private val enrollmentSuccessReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_ENROLLMENT_SUCCESS) {
                navigateToLauncher()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_provisioning_status)

        progressBar = findViewById(R.id.provisioning_progress)
        statusText  = findViewById(R.id.provisioning_status_text)
        errorText   = findViewById(R.id.provisioning_error_text)
        retryButton = findViewById(R.id.btn_provisioning_retry)

        retryButton.setOnClickListener { onRetry() }

        // If already enrolled, jump straight to launcher
        if (SecurePreferences.isProvisioningCompleted(this) &&
            SecurePreferences.isRegistered(this)) {
            navigateToLauncher()
            return
        }

        // Check if launched with an error
        val errorMessage = intent.getStringExtra(EXTRA_ERROR_MESSAGE)
        if (!errorMessage.isNullOrBlank()) {
            showError(errorMessage)
        } else {
            showEnrolling()
        }

        // Listen for success broadcasts from ProvisioningBootstrapService
        registerReceiver(
            enrollmentSuccessReceiver,
            IntentFilter(ACTION_ENROLLMENT_SUCCESS)
        )
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val errorMessage = intent?.getStringExtra(EXTRA_ERROR_MESSAGE)
        if (!errorMessage.isNullOrBlank()) {
            showError(errorMessage)
        } else {
            showEnrolling()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(enrollmentSuccessReceiver) } catch (_: Exception) {}
    }

    // Prevent back-press during enrollment
    @Deprecated("Overriding for kiosk enforcement")
    override fun onBackPressed() {
        // No-op — enrollment cannot be cancelled
    }

    // ── State helpers ─────────────────────────────────────────────────────────

    private fun showEnrolling() {
        progressBar.visibility = View.VISIBLE
        statusText.text        = getString(R.string.provisioning_enrolling)
        errorText.visibility   = View.GONE
        retryButton.visibility = View.GONE
    }

    private fun showError(message: String) {
        progressBar.visibility = View.GONE
        statusText.text        = getString(R.string.provisioning_failed)
        errorText.text         = message
        errorText.visibility   = View.VISIBLE
        retryButton.visibility = View.VISIBLE
    }

    private fun onRetry() {
        showEnrolling()
        // Re-schedule bootstrap service
        val serviceIntent = Intent(this, ProvisioningBootstrapService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun navigateToLauncher() {
        val intent = Intent(this, LauncherActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }
}
