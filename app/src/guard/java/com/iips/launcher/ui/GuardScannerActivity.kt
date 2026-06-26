package com.iips.launcher.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.iips.launcher.R
import com.iips.launcher.auth.GuardPairingRepository
import com.iips.launcher.network.models.PairingQrPayload
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class GuardScannerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GuardScanner"
        private const val REQUEST_CAMERA = 1001
    }

    @Inject
    lateinit var pairingRepository: GuardPairingRepository

    private lateinit var previewView: PreviewView
    private lateinit var tvStatus: TextView
    private lateinit var loadingProgress: android.widget.ProgressBar
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var resultDelivered = false
    private var isPairingInProgress = false
    private var imageAnalysis: ImageAnalysis? = null
    private var frameCount = 0

    private val scannerOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guard_scanner)

        previewView = findViewById(R.id.preview_view)
        tvStatus    = findViewById(R.id.tv_scan_hint)
        loadingProgress = findViewById(R.id.loading_progress)

        findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        checkPermissionAndStart()
    }

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            showStatus("Camera permission denied — cannot scan QR code", error = true)
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera() {
        showStatus("Scan Dotroid Launcher pairing QR", error = false)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                frameCount = 0
                bindAnalyzer()

                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis!!
                )

                previewView.setOnTouchListener { _, event ->
                    if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                        try {
                            val factory = previewView.meteringPointFactory
                            val point = factory.createPoint(event.x, event.y)
                            val action = androidx.camera.core.FocusMeteringAction.Builder(point, androidx.camera.core.FocusMeteringAction.FLAG_AF)
                                .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            camera.cameraControl.startFocusAndMetering(action)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to trigger tap-to-focus", e)
                        }
                    }
                    true
                }

            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                showStatus("Camera failed: ${e.message}", error = true)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindAnalyzer() {
        val scanner = BarcodeScanning.getClient(scannerOptions)
        val analyzer = BarcodeAnalyzer(scanner) { rawValue ->
            if (!resultDelivered && !isPairingInProgress) {
                resultDelivered = true
                runOnUiThread {
                    stopScanning()
                }
                handleScannedValue(rawValue)
            }
        }.apply {
            onFrameAnalyzed = {
                frameCount++
                if (frameCount % 10 == 0) {
                    runOnUiThread {
                        if (!isPairingInProgress && !resultDelivered) {
                            tvStatus.text = "Scanning... ($frameCount)"
                        }
                    }
                }
            }
        }
        imageAnalysis?.setAnalyzer(cameraExecutor, analyzer)
    }

    private fun stopScanning() {
        try {
            imageAnalysis?.clearAnalyzer()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear analyzer", e)
        }
    }

    private fun handleScannedValue(rawValue: String) {
        try {
            val payload = Gson().fromJson(rawValue, PairingQrPayload::class.java)
            if (payload == null || payload.pairingToken.isNullOrEmpty()) {
                throw Exception("Invalid pairing token")
            }
            
            if (payload.expiresAt < System.currentTimeMillis()) {
                showRescanState("Pairing code has expired. Please regenerate.")
                return
            }

            runOnUiThread {
                showConfirmationDialog(payload)
            }
        } catch (e: Exception) {
            showRescanState("Could not read pairing QR. Ensure you scanned the correct screen.")
        }
    }

    private fun showConfirmationDialog(payload: PairingQrPayload) {
        AlertDialog.Builder(this)
            .setTitle("Pair Device")
            .setMessage("Do you want to pair with device:\n${payload.deviceName}?")
            .setPositiveButton("Pair") { _, _ ->
                executePairing(payload.pairingToken)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                resultDelivered = false
                bindAnalyzer()
            }
            .setCancelable(false)
            .show()
    }

    private fun executePairing(token: String) {
        isPairingInProgress = true
        loadingProgress.visibility = View.VISIBLE
        findViewById<Button>(R.id.btn_cancel).isEnabled = false
        showStatus("Pairing device...", error = false)
        
        lifecycleScope.launch {
            val result = pairingRepository.pairDevice(token, "QR_SCAN")
            if (result.isSuccess) {
                val device = result.getOrThrow()
                Toast.makeText(
                    this@GuardScannerActivity,
                    if (device.securityStatus == "ACTIVE" || device.securityStatus == "NORMAL") "Device paired successfully!" else "Pairing request sent. Awaiting approval.",
                    Toast.LENGTH_LONG
                ).show()
                setResult(RESULT_OK)
                finish()
            } else {
                isPairingInProgress = false
                loadingProgress.visibility = View.GONE
                findViewById<Button>(R.id.btn_cancel).isEnabled = true
                val errorMsg = result.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                showRescanState("Pairing failed: $errorMsg")
            }
        }
    }

    private fun showRescanState(message: String) {
        runOnUiThread {
            showStatus(message, error = true)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            lifecycleScope.launch {
                kotlinx.coroutines.delay(2000)
                resultDelivered = false
                bindAnalyzer()
                showStatus("Scan Dotroid Launcher pairing QR", error = false)
            }
        }
    }

    private fun showStatus(message: String, error: Boolean) {
        runOnUiThread {
            tvStatus.text = message
            tvStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (error) android.R.color.holo_red_light else android.R.color.white
                )
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private class BarcodeAnalyzer(
        private val scanner: BarcodeScanner,
        private val onDetected: (String) -> Unit
    ) : ImageAnalysis.Analyzer {

        var onFrameAnalyzed: (() -> Unit)? = null

        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }

            val rotation = imageProxy.imageInfo.rotationDegrees

            try {
                val image = InputImage.fromMediaImage(mediaImage, rotation)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        for (barcode in barcodes) {
                            val raw = barcode.rawValue
                            if (!raw.isNullOrBlank()) {
                                onDetected(raw)
                                return@addOnSuccessListener
                            }
                        }
                    }
                    .addOnCompleteListener {
                        onFrameAnalyzed?.invoke()
                        imageProxy.close()
                    }
            } catch (e: Exception) {
                imageProxy.close()
            }
        }
    }
}
