package com.iips.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iips.launcher.BuildConfig
import com.iips.launcher.R
import com.iips.launcher.databinding.ActivityGuardLoginBinding
import com.iips.launcher.network.GuardAuthService
import com.iips.launcher.network.models.GuardLoginRequest
import com.iips.launcher.network.models.GuardSignupRequest
import com.iips.launcher.network.models.GuardForgotPasswordRequest
import com.iips.launcher.network.models.GuardGoogleLoginRequest
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GuardLoginActivity : AppCompatActivity() {

    @Inject
    lateinit var authService: GuardAuthService

    private lateinit var binding: ActivityGuardLoginBinding

    companion object {
        // Test credentials — visible in DEBUG builds only
        private const val TEST_EMAIL    = "test@dotroid.com"
        private const val TEST_PASSWORD = "Test@1234"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if user is already logged in
        val token = SecurePreferences.getGuardAuthToken(this)
        if (!token.isNullOrBlank()) {
            navigateToDashboard()
            return
        }

        binding = ActivityGuardLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupTestCredentials()
    }

    private fun setupTestCredentials() {
        // Show test banner only in debug / development builds
        if (BuildConfig.DEBUG) {
            binding.testCredentialsBanner.visibility = View.VISIBLE
            binding.btnUseTestCredentials.setOnClickListener {
                binding.edtIdentifier.setText(TEST_EMAIL)
                binding.edtPassword.setText(TEST_PASSWORD)
                Toast.makeText(this, "Test credentials auto-filled — tap Sign In", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupUI() {
        binding.btnLogin.setOnClickListener {
            performLogin()
        }
        binding.btnForgotPassword.setOnClickListener {
            showForgotPasswordDialog()
        }
        binding.btnSignup.setOnClickListener {
            showSignupDialog()
        }
        binding.btnGoogleSignin.setOnClickListener {
            showGoogleChooserDialog()
        }
    }

    private fun showGoogleChooserDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_google_chooser, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val layoutAccount1 = dialogView.findViewById<android.view.View>(R.id.layout_account_1)
        val layoutAccount2 = dialogView.findViewById<android.view.View>(R.id.layout_account_2)
        val layoutUseAnother = dialogView.findViewById<android.view.View>(R.id.layout_use_another)
        val layoutCustomInput = dialogView.findViewById<android.view.View>(R.id.layout_custom_input)
        val edtCustomEmail = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edt_custom_email)
        val btnCustomSubmit = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_custom_submit)
        val btnClose = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_close)
        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.dialog_progress_bar)

        btnClose.setOnClickListener { dialog.dismiss() }

        val handleGoogleLogin = { email: String, name: String ->
            progressBar.visibility = View.VISIBLE
            layoutAccount1.visibility = View.GONE
            layoutAccount2.visibility = View.GONE
            layoutUseAnother.visibility = View.GONE
            layoutCustomInput.visibility = View.GONE
            btnClose.visibility = View.GONE

            lifecycleScope.launch {
                try {
                    val fakeIdToken = "google-id-token-${System.currentTimeMillis()}"
                    val request = GuardGoogleLoginRequest(
                        idToken = fakeIdToken,
                        email = email,
                        name = name
                    )
                    val response = authService.googleLogin(request)
                    progressBar.visibility = View.GONE
                    
                    if (response.isSuccessful && response.body() != null) {
                        val apiResponse = response.body()!!
                        val loginResponse = apiResponse.data
                        if (apiResponse.responseCode == "SY00" && loginResponse != null) {
                            SecurePreferences.setGuardAuthToken(this@GuardLoginActivity, loginResponse.token)
                            SecurePreferences.setGuardRefreshToken(this@GuardLoginActivity, loginResponse.refreshToken)
                            
                            Toast.makeText(this@GuardLoginActivity, apiResponse.responseMessage ?: "Google Login Successful", Toast.LENGTH_SHORT).show()
                            navigateToDashboard()
                            dialog.dismiss()
                        } else {
                            Toast.makeText(this@GuardLoginActivity, apiResponse.responseMessage ?: "Google sign-in failed.", Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                        }
                    } else {
                        val errorString = response.errorBody()?.string()
                        val errorMsg = try {
                            if (!errorString.isNullOrBlank()) {
                                org.json.JSONObject(errorString).optString("responseMessage", "Google account login failed.")
                            } else {
                                "Google account login failed."
                            }
                        } catch (e: Exception) {
                            "Google account login failed."
                        }
                        Toast.makeText(this@GuardLoginActivity, errorMsg, Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                    }
                } catch (e: Exception) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@GuardLoginActivity, "Error connecting to server.", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                }
            }
            Unit
        }

        layoutAccount1.setOnClickListener {
            handleGoogleLogin("admin@dotroid.com", "Dotroid Admin")
        }

        layoutAccount2.setOnClickListener {
            handleGoogleLogin("guest@dotroid.com", "Guest Manager")
        }

        layoutUseAnother.setOnClickListener {
            layoutAccount1.visibility = View.GONE
            layoutAccount2.visibility = View.GONE
            layoutUseAnother.visibility = View.GONE
            layoutCustomInput.visibility = View.VISIBLE
        }

        btnCustomSubmit.setOnClickListener {
            val email = edtCustomEmail.text.toString().trim()
            if (email.isEmpty()) {
                edtCustomEmail.error = "Email address is required"
                return@setOnClickListener
            }
            val name = email.substringBefore("@").replaceFirstChar { it.uppercase() }
            handleGoogleLogin(email, name)
        }

        dialog.show()
    }

    private fun showForgotPasswordDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_guard_forgot_password, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val edtEmail = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edt_email)
        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.dialog_progress_bar)
        val btnSubmit = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_submit)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSubmit.setOnClickListener {
            val email = edtEmail.text.toString().trim()
            if (email.isEmpty()) {
                edtEmail.error = "Email address is required"
                return@setOnClickListener
            }

            progressBar.visibility = View.VISIBLE
            btnSubmit.isEnabled = false
            btnCancel.isEnabled = false

            lifecycleScope.launch {
                try {
                    val response = authService.forgotPassword(GuardForgotPasswordRequest(email))
                    progressBar.visibility = View.GONE
                    btnSubmit.isEnabled = true
                    btnCancel.isEnabled = true

                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        if (body.responseCode == "SY00") {
                            Toast.makeText(this@GuardLoginActivity, body.responseMessage ?: "Password recovery instructions sent.", Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                        } else {
                            edtEmail.error = body.responseMessage ?: "Failed to request password reset."
                        }
                    } else {
                        val errorString = response.errorBody()?.string()
                        val errorMsg = try {
                            if (!errorString.isNullOrBlank()) {
                                org.json.JSONObject(errorString).optString("responseMessage", "Failed to request password reset.")
                            } else {
                                "Failed to request password reset."
                            }
                        } catch (e: Exception) {
                            "Failed to request password reset."
                        }
                        edtEmail.error = errorMsg
                    }
                } catch (e: Exception) {
                    progressBar.visibility = View.GONE
                    btnSubmit.isEnabled = true
                    btnCancel.isEnabled = true
                    Toast.makeText(this@GuardLoginActivity, "Error connecting to server.", Toast.LENGTH_LONG).show()
                }
            }
        }

        dialog.show()
    }

    private fun showSignupDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_guard_signup, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val edtName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edt_name)
        val edtEmail = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edt_email)
        val edtPhone = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edt_phone)
        val edtPassword = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edt_password)
        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.dialog_progress_bar)
        val btnSubmit = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_submit)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSubmit.setOnClickListener {
            val name = edtName.text.toString().trim()
            val email = edtEmail.text.toString().trim()
            val phone = edtPhone.text.toString().trim()
            val password = edtPassword.text.toString().trim()

            var isValid = true
            if (name.isEmpty()) {
                edtName.error = "Full Name is required"
                isValid = false
            }
            if (email.isEmpty()) {
                edtEmail.error = "Email address is required"
                isValid = false
            }
            if (phone.isEmpty()) {
                edtPhone.error = "Phone number is required"
                isValid = false
            }
            if (password.isEmpty()) {
                edtPassword.error = "Password is required"
                isValid = false
            }

            if (!isValid) return@setOnClickListener

            progressBar.visibility = View.VISIBLE
            btnSubmit.isEnabled = false
            btnCancel.isEnabled = false

            lifecycleScope.launch {
                try {
                    val request = GuardSignupRequest(
                        name = name,
                        email = email,
                        phone = phone,
                        password = password
                    )
                    val response = authService.register(request)
                    progressBar.visibility = View.GONE
                    btnSubmit.isEnabled = true
                    btnCancel.isEnabled = true

                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        if (body.responseCode == "SY00") {
                            Toast.makeText(this@GuardLoginActivity, body.responseMessage ?: "Registration successful! You can now log in.", Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                        } else {
                            Toast.makeText(this@GuardLoginActivity, body.responseMessage ?: "Registration failed.", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        val errorString = response.errorBody()?.string()
                        val errorMsg = try {
                            if (!errorString.isNullOrBlank()) {
                                org.json.JSONObject(errorString).optString("responseMessage", "Registration failed.")
                            } else {
                                "Registration failed."
                            }
                        } catch (e: Exception) {
                            "Registration failed."
                        }
                        Toast.makeText(this@GuardLoginActivity, errorMsg, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    progressBar.visibility = View.GONE
                    btnSubmit.isEnabled = true
                    btnCancel.isEnabled = true
                    Toast.makeText(this@GuardLoginActivity, "Error connecting to server.", Toast.LENGTH_LONG).show()
                }
            }
        }

        dialog.show()
    }

    private fun performLogin() {
        val identifier = binding.edtIdentifier.text.toString().trim()
        val password = binding.edtPassword.text.toString().trim()

        if (identifier.isEmpty() || password.isEmpty()) {
            showError("Please enter your email/phone and password.")
            return
        }

        hideError()
        setLoading(true)

        val request = if (identifier.contains("@")) {
            GuardLoginRequest(email = identifier, password = password)
        } else {
            GuardLoginRequest(phone = identifier, password = password)
        }

        lifecycleScope.launch {
            try {
                val response = authService.login(request)
                setLoading(false)

                if (response.isSuccessful && response.body() != null) {
                    val apiResponse = response.body()!!
                    val loginResponse = apiResponse.data
                    
                    if (apiResponse.responseCode == "SY00" && loginResponse != null) {
                        SecurePreferences.setGuardAuthToken(this@GuardLoginActivity, loginResponse.token)
                        SecurePreferences.setGuardRefreshToken(this@GuardLoginActivity, loginResponse.refreshToken)
                        
                        Toast.makeText(this@GuardLoginActivity, apiResponse.responseMessage ?: "Login Successful", Toast.LENGTH_SHORT).show()
                        navigateToDashboard()
                    } else {
                        showError(apiResponse.responseMessage ?: "Login failed. Invalid response data.")
                    }
                } else {
                    val errorString = response.errorBody()?.string()
                    val errorMsg = try {
                        if (!errorString.isNullOrBlank()) {
                            org.json.JSONObject(errorString).optString("responseMessage", "Invalid login credentials.")
                        } else {
                            "Invalid login credentials."
                        }
                    } catch (e: Exception) {
                        "Invalid login credentials."
                    }
                    showError(errorMsg)
                }
            } catch (e: com.iips.launcher.network.NetworkFailureException) {
                setLoading(false)
                showError("Unable to connect to the server. Please check your internet connection.")
            } catch (e: Exception) {
                setLoading(false)
                showError("An unexpected error occurred: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun navigateToDashboard() {
        startActivity(Intent(this, GuardMainActivity::class.java))
        finish()
    }

    private fun setLoading(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !isLoading
        binding.edtIdentifier.isEnabled = !isLoading
        binding.edtPassword.isEnabled = !isLoading
    }

    private fun showError(message: String) {
        binding.txtErrorMessage.text = message
        binding.txtErrorMessage.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.txtErrorMessage.visibility = View.GONE
    }
}
