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
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.iips.launcher.R
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Full-screen QR scanner.
 *
 * Decodes QR codes. If the payload is JSON it extracts:
 *   - "enrollment_token"
 *   - "backend_url"
 * Otherwise the raw string is returned as the token.
 *
 * RESULT_OK extras:
 *   [EXTRA_TOKEN]       — enrollment token
 *   [EXTRA_BACKEND_URL] — backend URL (may be empty)
 */
class QrScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TOKEN       = "scanned_value"
        const val EXTRA_BACKEND_URL = "scanned_backend_url"
        private const val TAG = "QrScanner"
        private const val REQUEST_CAMERA = 1001
    }

    private lateinit var previewView: PreviewView
    private lateinit var tvStatus: TextView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var resultDelivered = false
    private var debugScanReceiver: android.content.BroadcastReceiver? = null

    // Target specific format for better performance on dense codes
    private val scannerOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scanner)

        previewView = findViewById(R.id.preview_view)
        tvStatus    = findViewById(R.id.tv_scan_hint)

        findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        checkPermissionAndStart()

        if (com.iips.launcher.BuildConfig.DEBUG) {
            debugScanReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: Intent?) {
                    val token = intent?.getStringExtra("token")
                    val backendUrl = intent?.getStringExtra("backend_url")
                    Log.i(TAG, "Simulating QR Scan via Debug Broadcast: token=$token, backendUrl=$backendUrl")
                    if (!resultDelivered && !token.isNullOrBlank()) {
                        resultDelivered = true
                        setResult(RESULT_OK, Intent().apply {
                            putExtra(EXTRA_TOKEN, token)
                            putExtra(EXTRA_BACKEND_URL, backendUrl ?: "")
                        })
                        finish()
                    }
                }
            }
            registerReceiver(debugScanReceiver, android.content.IntentFilter("com.iips.launcher.DEBUG_SCAN"))
        }
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

    // ── Camera ───────────────────────────────────────────────────────────────

    private fun startCamera() {
        showStatus("Point camera at QR code", error = false)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Set target resolution to match ImageAnalysis aspect ratio
                val preview = Preview.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                var frameCount = 0
                val scanner = BarcodeScanning.getClient(scannerOptions)
                imageAnalysis.setAnalyzer(cameraExecutor, BarcodeAnalyzer(scanner) { rawValue ->
                    if (!resultDelivered) {
                        resultDelivered = true
                        handleScannedValue(rawValue)
                    }
                }.apply {
                    onFrameAnalyzed = {
                        frameCount++
                        if (frameCount % 10 == 0) {
                            runOnUiThread {
                                tvStatus.text = "Scanning... ($frameCount frames)"
                            }
                        }
                    }
                })

                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )

                // Set up touch listener on previewView to perform tap-to-focus
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

                Log.i(TAG, "Camera bound successfully with matching aspect ratios and tap-to-focus")

            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                showStatus("Camera failed: ${e.message}", error = true)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    private fun handleScannedValue(rawValue: String) {
        Log.i(TAG, "Scanned raw length=${rawValue.length}")
        var token: String? = null
        var backendUrl: String? = null

        if (rawValue.trimStart().startsWith("{")) {
            try {
                val json = JSONObject(rawValue)
                token      = json.optString("enrollment_token").takeIf { it.isNotBlank() }
                backendUrl = json.optString("backend_url").takeIf { it.isNotBlank() }
                
                // If not found, check inside Android Enterprise extras bundle
                if (token == null && json.has("android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE")) {
                    val extras = json.optJSONObject("android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE")
                    if (extras != null) {
                        token = extras.optString("enrollment_token").takeIf { it.isNotBlank() }
                        if (backendUrl == null) {
                            backendUrl = extras.optString("backend_url").takeIf { it.isNotBlank() }
                        }
                    } else {
                        // Sometimes the bundle is encoded as a string
                        val extrasStr = json.optString("android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE")
                        if (extrasStr.isNotBlank() && extrasStr.trimStart().startsWith("{")) {
                            val extrasJson = JSONObject(extrasStr)
                            token = extrasJson.optString("enrollment_token").takeIf { it.isNotBlank() }
                            if (backendUrl == null) {
                                backendUrl = extrasJson.optString("backend_url").takeIf { it.isNotBlank() }
                            }
                        }
                    }
                }
                Log.i(TAG, "JSON QR — token=${token != null}, backend=$backendUrl")
            } catch (e: Exception) {
                Log.w(TAG, "Not valid JSON, using raw value: ${e.message}")
            }
        }

        if (token.isNullOrBlank()) token = rawValue.trim()

        if (token.isBlank()) {
            Log.w(TAG, "Empty token after parse — allowing rescan")
            resultDelivered = false
            showStatus("Could not read token. Try again.", error = true)
            return
        }

        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_TOKEN, token)
            putExtra(EXTRA_BACKEND_URL, backendUrl ?: "")
        })
        finish()
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
            tvStatus.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (com.iips.launcher.BuildConfig.DEBUG && debugScanReceiver != null) {
            try {
                unregisterReceiver(debugScanReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister debugScanReceiver", e)
            }
        }
    }

    // ── Analyser ─────────────────────────────────────────────────────────────

    private class BarcodeAnalyzer(
        private val scanner: BarcodeScanner,
        private val onDetected: (String) -> Unit
    ) : ImageAnalysis.Analyzer {

        var onFrameAnalyzed: (() -> Unit)? = null

        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                Log.w("BarcodeAnalyzer", "analyze: mediaImage is null")
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
                                Log.d("BarcodeAnalyzer", "detected raw value: $raw")
                                onDetected(raw)
                                return@addOnSuccessListener
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("BarcodeAnalyzer", "process failure: ${e.message}", e)
                    }
                    .addOnCompleteListener {
                        onFrameAnalyzed?.invoke()
                        imageProxy.close()
                    }
            } catch (e: Exception) {
                Log.e("BarcodeAnalyzer", "Error during frame analysis", e)
                imageProxy.close()
            }
        }
    }
}
