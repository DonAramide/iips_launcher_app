package com.iips.launcher.ui

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.iips.launcher.R
import com.iips.launcher.databinding.DialogAdminPasswordBinding
import com.iips.launcher.utils.SecurePreferences

class AdminPasswordDialogFragment(
    private val onSuccess: (Boolean) -> Unit
) : DialogFragment() {

    private var _binding: DialogAdminPasswordBinding? = null
    private val binding get() = _binding!!
    private var isPasswordVisible = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogAdminPasswordBinding.inflate(layoutInflater)

        setupPasswordField()
        setupEnterKeyListener()
        setupToggleButton()
        
        binding.passwordInput.requestFocus()

        binding.loginButton.setOnClickListener {
            validatePassword()
        }

        binding.cancelButton.setOnClickListener {
            onSuccess(false)
            dismiss()
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setTitle(R.string.enter_admin_password)
            .create()
    }

    private fun setupPasswordField() {
        // Custom transformation to show nothing when hidden (instead of dots)
        binding.passwordInput.transformationMethod = EmptyPasswordTransformationMethod()
    }

    private fun setupToggleButton() {
        // Set up password visibility toggle
        binding.passwordInputLayout.setEndIconOnClickListener {
            togglePasswordVisibility()
        }
    }

    private fun togglePasswordVisibility() {
        isPasswordVisible = !isPasswordVisible
        
        if (isPasswordVisible) {
            // Show password - change input type to text
            binding.passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            binding.passwordInput.transformationMethod = HideReturnsTransformationMethod.getInstance()
        } else {
            // Hide password (show nothing) - keep password input type but use custom transformation
            binding.passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            binding.passwordInput.transformationMethod = EmptyPasswordTransformationMethod()
        }
        
        // Move cursor to end
        binding.passwordInput.setSelection(binding.passwordInput.text?.length ?: 0)
    }

    private fun setupEnterKeyListener() {
        binding.passwordInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE || 
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                validatePassword()
                true
            } else {
                false
            }
        }
    }

    private fun validatePassword() {
        val enteredPassword = binding.passwordInput.text.toString()
        val correctPassword = SecurePreferences.getAdminPassword(requireContext())

        android.util.Log.d("AdminPasswordDialog", "Password validation - Entered: ${enteredPassword.isNotEmpty()}, Match: ${enteredPassword == correctPassword}")

        if (enteredPassword == correctPassword) {
            android.util.Log.d("AdminPasswordDialog", "Password correct, calling onSuccess callback")
            // Call success callback first, then dismiss
            onSuccess(true)
            // Use postDelayed to ensure callback completes before dismissing
            binding.passwordInput.postDelayed({
                android.util.Log.d("AdminPasswordDialog", "Dismissing dialog")
                dismiss()
            }, 150)
        } else {
            android.util.Log.d("AdminPasswordDialog", "Password incorrect")
            Toast.makeText(requireContext(), R.string.wrong_password, Toast.LENGTH_SHORT).show()
            binding.passwordInput.text?.clear()
            binding.passwordInput.requestFocus()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // Custom transformation method to show nothing instead of dots
    private class EmptyPasswordTransformationMethod : android.text.method.PasswordTransformationMethod() {
        override fun getTransformation(source: CharSequence?, view: android.view.View?): CharSequence {
            return EmptyPasswordCharSequence(source ?: "")
        }

        private class EmptyPasswordCharSequence(private val source: CharSequence) : CharSequence {
            override val length: Int get() = source.length
            override fun get(index: Int): Char = ' ' // Show space instead of dot
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
                return source.subSequence(startIndex, endIndex)
            }
        }
    }
}





