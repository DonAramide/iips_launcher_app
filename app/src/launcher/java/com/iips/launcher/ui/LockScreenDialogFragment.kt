package com.iips.launcher.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.CycleInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iips.launcher.R
import com.iips.launcher.storage.SecurePreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Windows-style full-screen lock screen dialog.
 *
 * Trigger: User manually taps the "Lock" item in the Dotroid popup menu.
 *
 * Recovery password formula: dd/mm/yy/@Dotroid/{dd+mm}
 * Example (20-09-2026) → "20/09/26/@Dotroid/29"
 */
class LockScreenDialogFragment : DialogFragment() {

    // Clock ticker
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 1000)
        }
    }

    // Views
    private lateinit var tvTime: TextView
    private lateinit var tvDate: TextView
    private lateinit var passwordArea: View
    private lateinit var recoveryArea: View
    private lateinit var setPasswordArea: View
    private lateinit var etPassword: EditText
    private lateinit var etRecovery: EditText
    private lateinit var etNewPassword: EditText
    private lateinit var etConfirmPassword: EditText
    private lateinit var tvError: TextView
    private lateinit var tvRecoveryError: TextView
    private lateinit var tvNewPwError: TextView
    private lateinit var btnUnlock: CardView
    private lateinit var btnForgot: TextView
    private lateinit var btnBack: TextView
    private lateinit var btnRecovery: CardView
    private lateinit var btnSavePassword: CardView
    private lateinit var btnTogglePassword: ImageView

    private var passwordVisible = false

    companion object {
        const val TAG = "LockScreenDialog"

        fun newInstance(): LockScreenDialogFragment = LockScreenDialogFragment()

        /**
         * Computes today's recovery password.
         * Format: dd/mm/yy/@Dotroid/{dd+mm}
         */
        fun computeRecoveryPassword(): String {
            val cal = Calendar.getInstance()
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val month = cal.get(Calendar.MONTH) + 1 // 0-indexed
            val year = cal.get(Calendar.YEAR) % 100
            val sum = day + month
            return "%02d/%02d/%02d/@Dotroid/%d".format(day, month, year, sum)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full-screen style
        setStyle(STYLE_NO_TITLE, R.style.Theme_LockScreen)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.apply {
            requestFeature(Window.FEATURE_NO_TITLE)
            // Full screen with no status bar gap
            setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        // NOT cancellable — must unlock or use recovery
        isCancelable = false
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_lock_screen, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        updateClock()
        clockHandler.post(clockRunnable)
        setupInteractions()

        // If no password has been set yet, go directly to set-password view
        val storedPw = SecurePreferences.getLockScreenPassword(requireContext())
        if (storedPw.isNullOrEmpty()) {
            showSetPasswordArea(afterRecovery = false)
        }
    }

    override fun onStart() {
        super.onStart()
        // Expand dialog to fill entire screen
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // Intercept Back key — do nothing (user must unlock)
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                true // consume — do not dismiss
            } else {
                false
            }
        }
    }

    override fun onDestroyView() {
        clockHandler.removeCallbacks(clockRunnable)
        super.onDestroyView()
    }

    // ─────────────────────────────────────────────
    // View binding
    // ─────────────────────────────────────────────

    private fun bindViews(root: View) {
        tvTime = root.findViewById(R.id.lock_time)
        tvDate = root.findViewById(R.id.lock_date)
        passwordArea = root.findViewById(R.id.lock_password_area)
        recoveryArea = root.findViewById(R.id.lock_recovery_area)
        setPasswordArea = root.findViewById(R.id.lock_set_password_area)
        etPassword = root.findViewById(R.id.lock_password_input)
        etRecovery = root.findViewById(R.id.lock_recovery_input)
        etNewPassword = root.findViewById(R.id.lock_new_password_input)
        etConfirmPassword = root.findViewById(R.id.lock_confirm_password_input)
        tvError = root.findViewById(R.id.lock_error_text)
        tvRecoveryError = root.findViewById(R.id.lock_recovery_error)
        tvNewPwError = root.findViewById(R.id.lock_new_pw_error)
        btnUnlock = root.findViewById(R.id.lock_unlock_btn)
        btnForgot = root.findViewById(R.id.lock_forgot_password)
        btnBack = root.findViewById(R.id.lock_back_to_password)
        btnRecovery = root.findViewById(R.id.lock_recovery_btn)
        btnSavePassword = root.findViewById(R.id.lock_save_password_btn)
        btnTogglePassword = root.findViewById(R.id.lock_toggle_password)
    }

    // ─────────────────────────────────────────────
    // Interactions
    // ─────────────────────────────────────────────

    private fun setupInteractions() {
        // Unlock
        btnUnlock.setOnClickListener { attemptUnlock() }
        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { attemptUnlock(); true } else false
        }

        // Eye toggle
        btnTogglePassword.setOnClickListener {
            passwordVisible = !passwordVisible
            etPassword.transformationMethod = if (passwordVisible)
                HideReturnsTransformationMethod.getInstance()
            else
                PasswordTransformationMethod.getInstance()
            etPassword.setSelection(etPassword.text?.length ?: 0)
        }

        // Forgot password → show recovery
        btnForgot.setOnClickListener {
            passwordArea.visibility = View.GONE
            recoveryArea.visibility = View.VISIBLE
            setPasswordArea.visibility = View.GONE
            etRecovery.requestFocus()
        }

        // Back → show password
        btnBack.setOnClickListener {
            recoveryArea.visibility = View.GONE
            passwordArea.visibility = View.VISIBLE
            setPasswordArea.visibility = View.GONE
        }

        // Verify recovery
        btnRecovery.setOnClickListener { attemptRecovery() }
        etRecovery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { attemptRecovery(); true } else false
        }

        // Save new password
        btnSavePassword.setOnClickListener { saveNewPassword() }
        etConfirmPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { saveNewPassword(); true } else false
        }
    }

    // ─────────────────────────────────────────────
    // Clock
    // ─────────────────────────────────────────────

    private fun updateClock() {
        val now = Date()
        tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        tvDate.text = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(now)
    }

    // ─────────────────────────────────────────────
    // Unlock logic
    // ─────────────────────────────────────────────

    private fun attemptUnlock() {
        val input = etPassword.text?.toString() ?: ""
        val stored = SecurePreferences.getLockScreenPassword(requireContext()) ?: ""

        if (input == stored && stored.isNotEmpty()) {
            // Correct — dismiss
            dismissAllowingStateLoss()
        } else {
            // Wrong — shake card + show error
            tvError.visibility = View.VISIBLE
            shakeView(btnUnlock)
            etPassword.text?.clear()
        }
    }

    // ─────────────────────────────────────────────
    // Recovery logic
    // ─────────────────────────────────────────────

    private fun attemptRecovery() {
        val input = etRecovery.text?.toString()?.trim() ?: ""
        val expected = computeRecoveryPassword()

        if (input == expected) {
            tvRecoveryError.visibility = View.INVISIBLE
            // Show set-new-password
            showSetPasswordArea(afterRecovery = true)
        } else {
            tvRecoveryError.visibility = View.VISIBLE
            shakeView(btnRecovery)
        }
    }

    // ─────────────────────────────────────────────
    // Save new password
    // ─────────────────────────────────────────────

    private fun saveNewPassword() {
        val newPw = etNewPassword.text?.toString() ?: ""
        val confirm = etConfirmPassword.text?.toString() ?: ""

        if (newPw.length < 4) {
            tvNewPwError.text = "Password must be at least 4 characters."
            tvNewPwError.visibility = View.VISIBLE
            return
        }
        if (newPw != confirm) {
            tvNewPwError.text = "Passwords do not match."
            tvNewPwError.visibility = View.VISIBLE
            shakeView(btnSavePassword)
            return
        }

        SecurePreferences.setLockScreenPassword(requireContext(), newPw)
        Toast.makeText(context, "Lock screen password saved!", Toast.LENGTH_SHORT).show()
        dismissAllowingStateLoss()
    }

    // ─────────────────────────────────────────────
    // View helpers
    // ─────────────────────────────────────────────

    private fun showSetPasswordArea(afterRecovery: Boolean) {
        passwordArea.visibility = View.GONE
        recoveryArea.visibility = View.GONE
        setPasswordArea.visibility = View.VISIBLE
        etNewPassword.requestFocus()
    }

    private fun shakeView(target: View) {
        val shakeX = ObjectAnimator.ofFloat(
            target, "translationX",
            0f, -18f, 18f, -14f, 14f, -8f, 8f, 0f
        ).apply {
            duration = 450
            interpolator = CycleInterpolator(1f)
        }
        AnimatorSet().apply {
            play(shakeX)
            start()
        }
    }
}
