package com.iips.launcher.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.iips.launcher.databinding.ActivityLauncherPairingBinding
import com.iips.launcher.network.LauncherPairingService
import com.iips.launcher.network.models.PairingQrPayload
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class LauncherPairingActivity : AppCompatActivity() {

    @Inject
    lateinit var pairingService: LauncherPairingService

    private lateinit var binding: ActivityLauncherPairingBinding
    private var countDownTimer: CountDownTimer? = null
    private var statusPollJob: Job? = null
    private var isPairingSuccessful = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hide root view initially until password is confirmed
        binding.root.visibility = View.INVISIBLE

        binding.btnClose.setOnClickListener {
            finish()
        }

        binding.btnRegenerate.setOnClickListener {
            startPairingFlow()
        }

        showAdminPasswordSecurityDialog()
    }

    private fun showAdminPasswordSecurityDialog() {
        val dialog = AdminPasswordDialogFragment { success ->
            if (success) {
                startPairingFlow()
            } else {
                finish()
            }
        }.apply {
            isCancelable = false
        }
        dialog.show(supportFragmentManager, "launcher_pairing_admin_auth")
    }

    private fun startPairingFlow() {
        binding.root.visibility = View.VISIBLE
        binding.loadingProgress.visibility = View.VISIBLE
        binding.qrImageView.visibility = View.GONE
        binding.expiredOverlay.visibility = View.GONE
        binding.userCodeText.visibility = View.GONE
        binding.countdownText.visibility = View.GONE
        binding.btnRegenerate.visibility = View.GONE
        binding.pairingSubtitle.text = "Requesting pairing token from Quasar..."

        statusPollJob?.cancel()
        countDownTimer?.cancel()

        val deviceId = SecurePreferences.getDeviceId(this)
        val deviceToken = SecurePreferences.getDeviceToken(this)

        if (deviceId.isNullOrEmpty() || deviceToken.isNullOrEmpty()) {
            binding.loadingProgress.visibility = View.GONE
            binding.pairingSubtitle.text = "Device not registered in MDM. Please complete onboarding first."
            return
        }

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    pairingService.getPairingToken(deviceId, "Bearer $deviceToken")
                }

                if (response.isSuccessful && response.body() != null) {
                    val tokenResponse = response.body()!!
                    val rawUrl = SecurePreferences.getProvisioningBackendUrl(this@LauncherPairingActivity)
                        ?: SecurePreferences.getBackendUrl(this@LauncherPairingActivity)
                        ?: SecurePreferences.getConfigUrl(this@LauncherPairingActivity)
                    val backendUrl = SecurePreferences.normalizeBackendUrl(rawUrl)

                    val expiresAtMs = try {
                        java.time.Instant.parse(tokenResponse.expiresAt).toEpochMilli()
                    } catch (e: Exception) {
                        tokenResponse.expiresAt.toLongOrNull() ?: (System.currentTimeMillis() + 900_000)
                    }

                    val payload = PairingQrPayload(
                        pairingToken = tokenResponse.pairingToken,
                        userCode = tokenResponse.userCode,
                        expiresAt = expiresAtMs,
                        deviceId = deviceId,
                        deviceName = android.os.Build.MODEL,
                        backendUrl = backendUrl
                    )

                    val payloadJson = Gson().toJson(payload)
                    val qrBitmap = generateQrCode(payloadJson, 512)

                    if (qrBitmap != null) {
                        binding.loadingProgress.visibility = View.GONE
                        binding.qrImageView.visibility = View.VISIBLE
                        binding.qrImageView.setImageBitmap(qrBitmap)
                        
                        if (!tokenResponse.userCode.isNullOrEmpty()) {
                            binding.userCodeText.text = "Pairing Code: ${tokenResponse.userCode}"
                            binding.userCodeText.visibility = View.VISIBLE
                        } else {
                            binding.userCodeText.visibility = View.GONE
                        }
                        
                        binding.pairingSubtitle.text = "Scan this QR code using the Dotroid Guard Manager app to link this device."
                        
                        startCountdown(expiresAtMs)
                        startPollingForStatus(deviceId, "Bearer $deviceToken")
                    } else {
                        showError("Failed to generate QR code bitmap.")
                    }
                } else {
                    showError("Quasar returned error: ${response.code()}")
                }
            } catch (e: Exception) {
                showError("Network connection error: ${e.localizedMessage}")
            }
        }
    }

    private fun startCountdown(expiresAtMs: Long) {
        val currentMs = System.currentTimeMillis()
        val durationMs = expiresAtMs - currentMs
        
        if (durationMs <= 0) {
            onTokenExpired()
            return
        }

        binding.countdownText.visibility = View.VISIBLE
        countDownTimer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val min = (millisUntilFinished / 1000) / 60
                val sec = (millisUntilFinished / 1000) % 60
                binding.countdownText.text = String.format("Expires in %02d:%02d", min, sec)
            }

            override fun onFinish() {
                onTokenExpired()
            }
        }.start()
    }

    private fun onTokenExpired() {
        binding.expiredOverlay.visibility = View.VISIBLE
        binding.countdownText.text = "Expired"
        binding.btnRegenerate.visibility = View.VISIBLE
        statusPollJob?.cancel()
    }

    private fun startPollingForStatus(deviceId: String, authHeader: String) {
        statusPollJob = lifecycleScope.launch {
            while (!isPairingSuccessful) {
                delay(3000) // Poll every 3 seconds
                try {
                    val response = withContext(Dispatchers.IO) {
                        pairingService.getPairingStatus(deviceId, authHeader)
                    }
                    if (response.isSuccessful && response.body() != null) {
                        val status = response.body()!!.status
                        if (status == "ACTIVE" || status == "NORMAL") {
                            isPairingSuccessful = true
                            onPairingSuccess(status)
                            break
                        }
                    }
                } catch (e: Exception) {
                    // Ignore transient errors while polling
                }
            }
        }
    }

    private fun onPairingSuccess(status: String) {
        countDownTimer?.cancel()
        binding.qrImageView.visibility = View.GONE
        binding.userCodeText.visibility = View.GONE
        binding.countdownText.visibility = View.GONE
        binding.expiredOverlay.visibility = View.GONE
        binding.loadingProgress.visibility = View.GONE
        
        if (status == "ACTIVE" || status == "NORMAL") {
            binding.pairingSubtitle.text = "Pairing Successful! Manager linked."
        } else {
            binding.pairingSubtitle.text = "Pairing Requested! Awaiting approval."
        }
        
        lifecycleScope.launch {
            delay(2000)
            finish()
        }
    }

    private fun showError(message: String) {
        binding.loadingProgress.visibility = View.GONE
        binding.pairingSubtitle.text = message
        binding.btnRegenerate.visibility = View.VISIBLE
    }

    private fun generateQrCode(text: String, size: Int): Bitmap? {
        return try {
            val bitMatrix: BitMatrix = MultiFormatWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                size,
                size
            )
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        statusPollJob?.cancel()
        super.onDestroy()
    }
}
