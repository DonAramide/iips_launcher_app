package com.iips.launcher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.material.textfield.TextInputEditText
import com.iips.launcher.R
import com.iips.launcher.network.DeviceEnrollmentManager
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class OnboardingActivity : AppCompatActivity() {

    private lateinit var viewFlipper: ViewFlipper
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    @Inject
    lateinit var enrollmentManager: DeviceEnrollmentManager

    // ── QR Scanner ───────────────────────────────────────────────────────────

    private val qrScanLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val token      = result.data?.getStringExtra(QrScannerActivity.EXTRA_TOKEN)
            val backendUrl = result.data?.getStringExtra(QrScannerActivity.EXTRA_BACKEND_URL)

            if (!token.isNullOrBlank()) {
                val inputEnrollmentToken = findViewById<TextInputEditText>(R.id.input_enrollment_token)
                inputEnrollmentToken.setText(token.trim())
                inputEnrollmentToken.setSelection(token.trim().length)
                Toast.makeText(this, "Token populated from QR", Toast.LENGTH_SHORT).show()
            }
            if (!backendUrl.isNullOrBlank()) {
                val normalized = com.iips.launcher.policy.DeviceAdminReceiver.normalizeBackendUrl(backendUrl)
                SecurePreferences.setProvisioningBackendUrl(this, normalized)
                SecurePreferences.setBackendUrl(this, normalized)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        
        // Fullscreen Immersive Mode
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        if (com.iips.launcher.policy.DeviceAdminReceiver.isDeviceOwner(this)) {
            com.iips.launcher.policy.DeviceController.setStatusBarLocked(this, true)
        }
        
        SecurePreferences.setDeviceState(this, SecurePreferences.STATE_ONBOARDING)

        viewFlipper = findViewById(R.id.view_flipper)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupWelcomeStep()
        setupBusinessStep()
        setupSyncStep()
        setupFinishStep()
        setupWifiButton()
        displaySerialNumber()
    }

    private fun displaySerialNumber() {
        val serial = com.iips.launcher.security.SecurityUtils.getSerialNumber(this)
        findViewById<TextView>(R.id.tv_serial_number).text = "SN: $serial"
    }

    private fun setupWifiButton() {
        findViewById<View>(R.id.btn_wifi_settings).setOnClickListener {
            startActivity(Intent(this, WifiSetupActivity::class.java))
        }
    }

    private fun setupWelcomeStep() {
        val welcomeLogo = findViewById<ImageView>(R.id.welcome_logo)
        val animation = AnimationUtils.loadAnimation(this, R.anim.welcome_logo_animation)
        welcomeLogo.startAnimation(animation)

        val btnGetStarted = findViewById<Button>(R.id.btn_get_started)
        btnGetStarted.setOnClickListener {
            viewFlipper.showNext()
        }
        
        updateWifiStatus()
    }

    private fun updateWifiStatus() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val tvWifiStatus = findViewById<TextView>(R.id.tv_wifi_status)
        
        val wifiInfo = wifiManager.connectionInfo
        if (wifiInfo != null && wifiInfo.networkId != -1) {
            val ssid = wifiInfo.ssid.removeSurrounding("\"")
            tvWifiStatus.text = "Connected to: $ssid"
            tvWifiStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
        } else {
            tvWifiStatus.text = "Not Connected"
            tvWifiStatus.setTextColor(android.graphics.Color.parseColor("#FF9800"))
        }
    }

    override fun onResume() {
        super.onResume()
        updateWifiStatus()
    }

    private fun setupBusinessStep() {
        val btnContinue = findViewById<Button>(R.id.btn_setup_continue)
        val inputEnrollmentToken = findViewById<TextInputEditText>(R.id.input_enrollment_token)
        val inputBusinessName = findViewById<TextInputEditText>(R.id.input_business_name)
        val inputPassword = findViewById<TextInputEditText>(R.id.input_admin_password)
        val inputConfirm = findViewById<TextInputEditText>(R.id.input_confirm_password)
        val inputAgentCode = findViewById<TextInputEditText>(R.id.input_agent_code)

        val enrollmentTokenLayout = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.enrollment_token_layout)
        enrollmentTokenLayout.setEndIconOnClickListener {
            qrScanLauncher.launch(Intent(this, QrScannerActivity::class.java))
        }

        btnContinue.setOnClickListener {
            val enrollmentToken = inputEnrollmentToken.text.toString().trim()
            val businessName = inputBusinessName.text.toString().trim()
            val password = inputPassword.text.toString().trim()
            val confirm = inputConfirm.text.toString().trim()
            val agentCode = inputAgentCode.text.toString().trim()

            if (enrollmentToken.isEmpty()) {
                inputEnrollmentToken.error = "Enrollment Token is required"
                return@setOnClickListener
            }

            if (businessName.isEmpty()) {
                inputBusinessName.error = "Business Name is required"
                return@setOnClickListener
            }

            if (password.isEmpty() || password.length < 4) {
                inputPassword.error = "Password must be at least 4 characters"
                return@setOnClickListener
            }

            if (password != confirm) {
                inputConfirm.error = "Passwords do not match"
                return@setOnClickListener
            }

            val hashedPassword = com.iips.launcher.security.SecurityUtils.sha256(password)
            SecurePreferences.setBusinessName(this, businessName)
            SecurePreferences.setAdminPassword(this, hashedPassword)
            SecurePreferences.setEnrollmentToken(this, enrollmentToken)

            // Do NOT call viewFlipper.showNext() yet. 
            // Stay on this screen while we verify the token.
            startRegistrationProcess(enrollmentToken, btnContinue, agentCode)
        }
    }

    private fun startRegistrationProcess(enrollmentToken: String? = null, actionButton: Button? = null, agentCode: String? = null) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    actionButton?.isEnabled = false
                    actionButton?.text = "Enrolling..."
                }
                
                requestLocationPermission()
                
                val success = enrollmentManager.enrollIfNeeded(enrollmentToken, agentCode)
                
                if (success) {
                    withContext(Dispatchers.Main) {
                        // Success! Move to the final screen

                        SecurePreferences.setDeviceState(this@OnboardingActivity, SecurePreferences.STATE_REGISTERED)
                        
                        // We skip the sync step flipper and go to finish
                        viewFlipper.displayedChild = 3 // Step 4: Finished
                    }
                } else {
                    throw Exception("Enrollment failed (Invalid Token)")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    actionButton?.isEnabled = true
                    actionButton?.text = "Continue"
                    Toast.makeText(this@OnboardingActivity, "Registration failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupSyncStep() {
        // This step is now bypassed in the success path or used for retries
        findViewById<Button>(R.id.btn_retry_sync).setOnClickListener {
            startRegistrationProcess()
        }
    }

    private fun setupFinishStep() {
        findViewById<Button>(R.id.btn_finish).setOnClickListener {
            val intent = Intent(this, PendingActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Location permission is required for policy enforcement.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    override fun onBackPressed() {
        // Do nothing
    }
}
