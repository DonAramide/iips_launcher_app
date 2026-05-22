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

                val preview = Preview.Builder().build().also {
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
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                Log.i(TAG, "Camera bound successfully")

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
        private var frameCounter = 0

        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                Log.w("BarcodeAnalyzer", "analyze: mediaImage is null")
                imageProxy.close()
                return
            }

            val rotation = imageProxy.imageInfo.rotationDegrees
            val width = imageProxy.width
            val height = imageProxy.height
            val currentFrame = frameCounter++

            try {
                if (currentFrame % 2 == 0) {
                    // Process original frame
                    Log.d("BarcodeAnalyzer", "analyze frame $currentFrame: original ${width}x${height}, format=${imageProxy.format}, rotation=$rotation")
                    val image = InputImage.fromMediaImage(mediaImage, rotation)
                    processScanner(image, imageProxy, isInverted = false)
                } else {
                    // Process inverted grayscale frame
                    Log.d("BarcodeAnalyzer", "analyze frame $currentFrame: inverted ${width}x${height}, rotation=$rotation")
                    val invertedNv21Bytes = invertYPlaneToNv21(imageProxy)
                    val image = InputImage.fromByteArray(
                        invertedNv21Bytes,
                        width,
                        height,
                        rotation,
                        InputImage.IMAGE_FORMAT_NV21
                    )
                    processScanner(image, imageProxy, isInverted = true)
                }
            } catch (e: Exception) {
                Log.e("BarcodeAnalyzer", "Error during frame analysis selection", e)
                imageProxy.close()
            }
        }

        private fun processScanner(image: InputImage, imageProxy: ImageProxy, isInverted: Boolean) {
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty()) {
                        Log.d("BarcodeAnalyzer", "process success: detected ${barcodes.size} barcodes (isInverted=$isInverted)")
                    }
                    for (barcode in barcodes) {
                        val raw = barcode.rawValue
                        Log.d("BarcodeAnalyzer", "detected raw value: $raw")
                        if (!raw.isNullOrBlank()) {
                            onDetected(raw)
                            return@addOnSuccessListener
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("BarcodeAnalyzer", "process failure (isInverted=$isInverted): ${e.message}", e)
                }
                .addOnCompleteListener {
                    onFrameAnalyzed?.invoke()
                    imageProxy.close()
                }
        }

        private fun invertYPlaneToNv21(imageProxy: ImageProxy): ByteArray {
            val width = imageProxy.width
            val height = imageProxy.height
            val nv21 = ByteArray(width * height * 3 / 2)
            
            val yPlane = imageProxy.planes[0]
            val yBuffer = yPlane.buffer
            yBuffer.rewind()
            val ySize = width * height
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride
            
            if (pixelStride == 1 && rowStride == width) {
                yBuffer.get(nv21, 0, ySize)
                for (i in 0 until ySize) {
                    nv21[i] = (255 - (nv21[i].toInt() and 0xFF)).toByte()
                }
            } else {
                var nv21Idx = 0
                val rowBytes = ByteArray(rowStride)
                for (row in 0 until height) {
                    yBuffer.position(row * rowStride)
                    val length = Math.min(rowStride, yBuffer.remaining())
                    yBuffer.get(rowBytes, 0, length)
                    for (col in 0 until width) {
                        nv21[nv21Idx++] = (255 - (rowBytes[col * pixelStride].toInt() and 0xFF)).toByte()
                    }
                }
            }
            
            val vuStart = width * height
            val vuSize = width * height / 2
            java.util.Arrays.fill(nv21, vuStart, vuStart + vuSize, 128.toByte())
            
            return nv21
        }
    }
}
