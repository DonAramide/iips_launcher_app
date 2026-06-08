package com.iips.launcher.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import com.iips.launcher.R
import com.iips.launcher.databinding.ActivityKioskLockBinding
import com.iips.launcher.policy.KioskLockManager
import com.iips.launcher.storage.SecurePreferences

class KioskLockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKioskLockBinding
    private var enteredPin = StringBuilder()
    private var expectedPin = ""

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "device_policy_snapshot") {
            val snapshot = SecurePreferences.getDevicePolicySnapshot(this)
            val newPinEnabled = snapshot?.kioskPinEnabled ?: false
            val newPin = snapshot?.kioskPin ?: ""
            
            if (!newPinEnabled || newPin.isEmpty()) {
                KioskLockManager.markUnlocked()
                finish()
            } else if (newPin != expectedPin) {
                expectedPin = newPin
                enteredPin.clear()
                updatePinIndicators()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Hide system UI (StatusBar & NavigationBar) to lock down layout
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        binding = ActivityKioskLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Retrieve configured PIN from active policy
        val snapshot = SecurePreferences.getDevicePolicySnapshot(this)
        expectedPin = snapshot?.kioskPin ?: ""

        // Fallback safety: if pin is empty, bypass immediately
        if (expectedPin.isEmpty()) {
            KioskLockManager.markUnlocked()
            finish()
            return
        }

        // Register policy change listener
        try {
            SecurePreferences.getEncryptedPrefs(this)
                .registerOnSharedPreferenceChangeListener(prefListener)
        } catch (e: Exception) {
            android.util.Log.e("KioskLockActivity", "Failed to register prefs listener: ${e.message}")
        }

        setupKeyboard()
        updatePinIndicators()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            SecurePreferences.getEncryptedPrefs(this)
                .unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onResume() {
        super.onResume()
        // Aggressively re-apply immersive flags
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onBackPressed() {
        // Intercept back press and block it to prevent bypassing lock screen
        binding.pinIndicatorsContainer.startAnimation(
            AnimationUtils.loadAnimation(this, android.R.anim.slide_in_left)
        )
    }

    private fun setupKeyboard() {
        val clickListener = View.OnClickListener { v ->
            val buttonText = (v as? com.google.android.material.button.MaterialButton)?.text?.toString()
            if (buttonText != null && buttonText.length == 1 && buttonText[0].isDigit()) {
                appendDigit(buttonText)
            }
        }

        // Bind standard digit keys
        binding.btn0.setOnClickListener(clickListener)
        binding.btn1.setOnClickListener(clickListener)
        binding.btn2.setOnClickListener(clickListener)
        binding.btn3.setOnClickListener(clickListener)
        binding.btn4.setOnClickListener(clickListener)
        binding.btn5.setOnClickListener(clickListener)
        binding.btn6.setOnClickListener(clickListener)
        binding.btn7.setOnClickListener(clickListener)
        binding.btn8.setOnClickListener(clickListener)
        binding.btn9.setOnClickListener(clickListener)

        // Clear and Delete actions
        binding.btnClear.setOnClickListener {
            enteredPin.clear()
            updatePinIndicators()
            binding.errorMessageText.text = "Enter PIN to unlock"
            binding.errorMessageText.setTextColor(android.graphics.Color.parseColor("#64748B"))
        }

        binding.btnDelete.setOnClickListener {
            if (enteredPin.isNotEmpty()) {
                enteredPin.deleteCharAt(enteredPin.length - 1)
                updatePinIndicators()
                binding.errorMessageText.text = "Enter PIN to unlock"
                binding.errorMessageText.setTextColor(android.graphics.Color.parseColor("#64748B"))
            }
        }
    }

    private fun appendDigit(digit: String) {
        if (enteredPin.length < expectedPin.length) {
            enteredPin.append(digit)
            updatePinIndicators()

            if (enteredPin.length == expectedPin.length) {
                // Post verification on UI thread with short delay for tactile feedback
                Handler(Looper.getMainLooper()).postDelayed({
                    verifyPin()
                }, 150)
            }
        }
    }

    private fun verifyPin() {
        if (enteredPin.toString() == expectedPin) {
            KioskLockManager.markUnlocked()
            finish()
        } else {
            // Shake dots and display error state
            val shakeAnim = AnimationUtils.loadAnimation(this, android.R.anim.fade_in) // Simple default shake fallback
            binding.pinIndicatorsContainer.startAnimation(shakeAnim)
            
            binding.errorMessageText.text = "Incorrect PIN. Try again."
            binding.errorMessageText.setTextColor(android.graphics.Color.parseColor("#EF4444"))
            
            // Clear input
            enteredPin.clear()
            updatePinIndicators()
        }
    }

    private fun updatePinIndicators() {
        val count = enteredPin.length
        binding.dot1.setBackgroundResource(if (count >= 1) R.drawable.pin_dot_filled else R.drawable.pin_dot_empty)
        binding.dot2.setBackgroundResource(if (count >= 2) R.drawable.pin_dot_filled else R.drawable.pin_dot_empty)
        binding.dot3.setBackgroundResource(if (count >= 3) R.drawable.pin_dot_filled else R.drawable.pin_dot_empty)
        binding.dot4.setBackgroundResource(if (count >= 4) R.drawable.pin_dot_filled else R.drawable.pin_dot_empty)
    }
}
