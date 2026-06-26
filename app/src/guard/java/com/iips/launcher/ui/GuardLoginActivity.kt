package com.iips.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iips.launcher.BuildConfig
import com.iips.launcher.databinding.ActivityGuardLoginBinding
import com.iips.launcher.network.GuardAuthService
import com.iips.launcher.network.models.GuardLoginRequest
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
