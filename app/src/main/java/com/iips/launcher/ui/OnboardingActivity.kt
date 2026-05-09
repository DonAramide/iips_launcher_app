package com.iips.launcher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.provider.Settings
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageView
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
import com.iips.launcher.config.MDMManager
import com.iips.launcher.utils.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnboardingActivity : AppCompatActivity() {

    private lateinit var viewFlipper: ViewFlipper
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        if (com.iips.launcher.device.DeviceAdminReceiver.isDeviceOwner(this)) {
            startLockTask()
        }
        
        SecurePreferences.setDeviceState(this, SecurePreferences.STATE_ONBOARDING)

        viewFlipper = findViewById(R.id.view_flipper)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupWelcomeStep()
        setupBusinessStep()
        setupSyncStep()
        setupFinishStep()
    }

    private fun setupWelcomeStep() {
        val welcomeLogo = findViewById<ImageView>(R.id.welcome_logo)
        val animation = AnimationUtils.loadAnimation(this, R.anim.welcome_logo_animation)
        welcomeLogo.startAnimation(animation)

        val btnGetStarted = findViewById<Button>(R.id.btn_get_started)
        btnGetStarted.setOnClickListener {
            viewFlipper.showNext()
            updateProgress(40)
        }
    }

    private fun setupBusinessStep() {
        val btnContinue = findViewById<Button>(R.id.btn_setup_continue)
        val inputEnrollmentToken = findViewById<TextInputEditText>(R.id.input_enrollment_token)
        val inputBusinessName = findViewById<TextInputEditText>(R.id.input_business_name)
        val inputPassword = findViewById<TextInputEditText>(R.id.input_admin_password)
        val inputConfirm = findViewById<TextInputEditText>(R.id.input_confirm_password)

        btnContinue.setOnClickListener {
            val enrollmentToken = inputEnrollmentToken.text.toString().trim()
            val businessName = inputBusinessName.text.toString().trim()
            val password = inputPassword.text.toString()
            val confirm = inputConfirm.text.toString()

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

            // Hash password and save to secure storage
            val hashedPassword = com.iips.launcher.utils.HashUtils.sha256(password)
            SecurePreferences.setBusinessName(this, businessName)
            SecurePreferences.setAdminPassword(this, hashedPassword)
            SecurePreferences.setEnrollmentToken(this, enrollmentToken)

            viewFlipper.showNext()
            updateProgress(60)
            
            // Proceed to registration and location
            startRegistrationAndLocationProcess(enrollmentToken)
        }
    }

    private fun startRegistrationAndLocationProcess(enrollmentToken: String? = null) {
        lifecycleScope.launch {
            try {
                // 1. Request location permission first
                requestLocationPermission()
                
                // 2. Register Device
                MDMManager.registerIfNeeded(this@OnboardingActivity, enrollmentToken)
                
                // 3. Location is handled via callback and MDMManager heartbeat/policy sync will take care of sending it.
                // We will wait for MDM registration.
                
                updateProgress(80)
                
                // Move to Finish step
                withContext(Dispatchers.Main) {
                    viewFlipper.showNext()
                    updateProgress(100)
                    SecurePreferences.setDeviceState(this@OnboardingActivity, SecurePreferences.STATE_REGISTERED)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OnboardingActivity, "Registration failed. Please retry.", Toast.LENGTH_LONG).show()
                    findViewById<Button>(R.id.btn_retry_sync).visibility = android.view.View.VISIBLE
                }
            }
        }
    }

    private fun setupSyncStep() {
        findViewById<Button>(R.id.btn_retry_sync).setOnClickListener {
            it.visibility = android.view.View.GONE
            startRegistrationAndLocationProcess()
        }
    }

    private fun setupFinishStep() {
        findViewById<Button>(R.id.btn_finish).setOnClickListener {
            val intent = Intent(this, PendingActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    private fun updateProgress(progress: Int) {
        val progressBar = findViewById<android.widget.ProgressBar>(R.id.onboarding_progress)
        progressBar.progress = progress
    }

    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            getLocation()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getLocation()
            } else {
                Toast.makeText(this, "Location permission is required for policy enforcement.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLocation() {
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                // Location retrieved successfully.
                // GeofenceService / MDMManager will pick this up automatically during sync.
            }
        }
    }
    
    // Prevent backing out of onboarding
    override fun onBackPressed() {
        // Do nothing
    }
}
