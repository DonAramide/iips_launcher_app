package com.iips.launcher.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iips.launcher.R
import com.iips.launcher.databinding.DialogChangePasswordBinding
import com.iips.launcher.storage.SecurePreferences

class ChangePasswordDialogFragment(
    private val onPasswordChanged: () -> Unit
) : DialogFragment() {

    private var _binding: DialogChangePasswordBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogChangePasswordBinding.inflate(layoutInflater)

        binding.saveButton.setOnClickListener {
            val newPassword = binding.newPasswordInput.text.toString()
            val confirmPassword = binding.confirmPasswordInput.text.toString()

            if (newPassword.isEmpty()) {
                Toast.makeText(requireContext(), R.string.password_cannot_be_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newPassword != confirmPassword) {
                Toast.makeText(requireContext(), R.string.passwords_dont_match, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            SecurePreferences.setAdminPassword(requireContext(), newPassword)
            onPasswordChanged()
            dismiss()
        }

        binding.cancelButton.setOnClickListener {
            dismiss()
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setTitle(R.string.change_password)
            .create()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}





