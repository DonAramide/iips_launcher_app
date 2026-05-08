package com.iips.launcher.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.iips.launcher.BuildConfig
import com.iips.launcher.config.ConfigService
import com.iips.launcher.config.EnrollmentRequest
import com.iips.launcher.config.EnrollmentResponse
import com.iips.launcher.config.MDMManager
import com.iips.launcher.ui.LauncherActivity
import com.iips.launcher.ui.ProvisioningStatusActivity
import com.iips.launcher.utils.SecurePreferences
import com.iips.launcher.utils.SecurityUtils
import com.iips.launcher.workers.EnrollmentRetryWorker
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * Foreground service that drives the full Android Enterprise bootstrap sequence:
 *
 *  1. Verify Device Owner status
 *  2. Guard against re-enrollment (idempotent)
 *  3. Wait for network connectivity (up to [NETWORK_TIMEOUT_MS])
 *  4. Build [EnrollmentRequest] with full device metadata
 *  5. POST /api/v1/devices/enroll with exponential backoff (up to [MAX_RETRIES])
 *  6a. Success → persist credentials, set state ACTIVE, clear one-time extras,
 *               start MDM services, launch LauncherActivity
 *  6b. Failure → schedule [EnrollmentRetryWorker], surface error to
 *               [ProvisioningStatusActivity]
 *
 * Security contract:
 *  - enrollment_token is NEVER logged (token guard applied at all log sites)
 *  - [SecurePreferences.clearProvisioningExtras] is called immediately on success
 *  - backend_url validated again before building Retrofit instance
 */
class ProvisioningBootstrapService : Service() {

    companion object {
        private const val TAG = "BootstrapService"
        private const val NOTIF_CHANNEL_ID  = "dotoid_provisioning"
        private const val NOTIF_ID          = 2001

        private const val MAX_RETRIES          = 5
        private const val BASE_BACKOFF_MS      = 1_000L
        private const val MAX_BACKOFF_MS       = 30_000L
        private const val NETWORK_TIMEOUT_MS   = 60_000L
        private const val NETWORK_POLL_INTERVAL = 2_000L

        /** Intent extra set by [EnrollmentRetryWorker] to signal a retry attempt. */
        const val EXTRA_IS_RETRY = "is_retry"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("Enrolling device into Dotoid MDM…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isRetry = intent?.getBooleanExtra(EXTRA_IS_RETRY, false) ?: false
        Log.i(TAG, "onStartCommand — isRetry=$isRetry")
        scope.launch { runBootstrap(isRetry) }
        return START_NOT_STICKY // Don't auto-restart; EnrollmentRetryWorker handles recovery
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Bootstrap sequence ───────────────────────────────────────────────────

    private suspend fun runBootstrap(isRetry: Boolean) {
        try {
            // ── 1. Device Owner check ────────────────────────────────────────
            if (!DeviceAdminReceiver.isDeviceOwner(this)) {
                Log.e(TAG, "Not Device Owner — aborting bootstrap")
                broadcastError("Device is not set as Device Owner. Factory reset and re-provision.")
                stopSelf()
                return
            }

            // ── 2. Idempotency guard ─────────────────────────────────────────
            if (SecurePreferences.isProvisioningCompleted(this) &&
                SecurePreferences.isRegistered(this)) {
                Log.i(TAG, "Provisioning already completed and device registered — skipping")
                launchLauncher()
                stopSelf()
                return
            }

            // ── 3. Network wait ──────────────────────────────────────────────
            updateNotification("Waiting for network connectivity…")
            if (!waitForNetwork()) {
                Log.e(TAG, "Network unavailable after ${NETWORK_TIMEOUT_MS / 1000}s — aborting")
                broadcastError("No network connection. Connect to Wi-Fi and retry.")
                scheduleRetry()
                stopSelf()
                return
            }
            Log.i(TAG, "Network available — proceeding with enrollment")

            // ── 4. Read stored provisioning extras ───────────────────────────
            val enrollmentToken = SecurePreferences.getEnrollmentToken(this)
            if (enrollmentToken.isNullOrBlank()) {
                Log.e(TAG, "No enrollment token stored — cannot enroll")
                broadcastError("Missing enrollment token. Re-provision device.")
                stopSelf()
                return
            }

            val tenantId     = SecurePreferences.getTenantId(this)
            val policyGroupId = SecurePreferences.getPolicyGroupId(this)
            val backendUrl   = resolveBackendUrl()

            // ── 5. Build enrollment request ──────────────────────────────────
            updateNotification("Enrolling into Quasar MDM…")
            val fingerprintHash = SecurityUtils.calculateFingerprintHash(this)
            val serial = getSerialNumber()

            val request = EnrollmentRequest(
                enrollmentToken = enrollmentToken,   // ⚠ never log this field
                tenantId        = tenantId,
                policyGroupId   = policyGroupId,
                manufacturer    = Build.MANUFACTURER,
                model           = Build.MODEL,
                androidVersion  = Build.VERSION.RELEASE,
                serialNumber    = serial,
                fingerprint     = Build.FINGERPRINT,
                fingerprintHash = fingerprintHash,
                appVersion      = BuildConfig.VERSION_NAME
            )

            // ── 6. POST with exponential backoff ─────────────────────────────
            val service = buildRetrofitService(backendUrl)
            val response = enrollWithRetry(service, request)

            if (response != null && response.isSuccessful) {
                val body = response.body()!!
                onEnrollmentSuccess(body, fingerprintHash, enrollmentToken)
            } else {
                val code = response?.code() ?: -1
                val errBody = response?.errorBody()?.string() ?: "no body"
                Log.e(TAG, "Enrollment failed — HTTP $code: $errBody")
                
                val isTerminal = code in 400..499
                broadcastError(if (isTerminal) "Invalid enrollment token (HTTP $code). Re-provision device." 
                               else "Enrollment failed (HTTP $code). Check network and retry.")
                
                if (!isTerminal) {
                    scheduleRetry()
                } else {
                    Log.e(TAG, "Terminal error $code — will NOT schedule retry")
                }
                stopSelf()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during bootstrap", e)
            broadcastError("Unexpected error: ${e.message}")
            scheduleRetry()
            stopSelf()
        }
    }

    // ── Enrollment with exponential backoff ──────────────────────────────────

    private suspend fun enrollWithRetry(
        service: ConfigService,
        request: EnrollmentRequest
    ): Response<EnrollmentResponse>? {
        var lastResponse: Response<EnrollmentResponse>? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                Log.i(TAG, "Enrollment attempt $attempt/$MAX_RETRIES")
                val response = service.enrollDevice(request)
                if (response.isSuccessful) return response

                // 4xx errors are terminal — no point retrying invalid tokens
                val code = response.code()
                if (code in 400..499) {
                    Log.e(TAG, "Terminal enrollment error $code — not retrying")
                    return response
                }
                lastResponse = response
                Log.w(TAG, "Enrollment attempt $attempt failed (HTTP $code)")
            } catch (e: Exception) {
                Log.w(TAG, "Enrollment attempt $attempt threw: ${e.message}")
            }

            if (attempt < MAX_RETRIES) {
                val backoff = calculateBackoff(attempt)
                Log.d(TAG, "Retrying in ${backoff}ms…")
                updateNotification("Enrollment retry $attempt/$MAX_RETRIES — waiting…")
                delay(backoff)
            }
        }
        return lastResponse
    }

    /** Exponential backoff with jitter: 1s → 2s → 4s → 8s → 16s (capped at [MAX_BACKOFF_MS]). */
    private fun calculateBackoff(attempt: Int): Long {
        val expo = (BASE_BACKOFF_MS * 2.0.pow(attempt - 1)).toLong()
        val capped = min(expo, MAX_BACKOFF_MS)
        // ±20% jitter to avoid thundering herd
        val jitter = (capped * 0.2 * Math.random()).toLong()
        return capped + jitter
    }

    // ── Success handler ──────────────────────────────────────────────────────

    private fun onEnrollmentSuccess(
        response: EnrollmentResponse,
        fingerprintHash: String,
        enrollmentToken: String  // kept for fingerprint storage only, NOT logged
    ) {
        Log.i(TAG, "Enrollment successful — device_id=${response.deviceId}")

        // Persist credentials
        SecurePreferences.setDeviceId(this, response.deviceId)
        SecurePreferences.setDeviceToken(this, response.accessToken)
        SecurePreferences.setTokenExpiresAt(
            this,
            (System.currentTimeMillis() / 1000) + response.expiresIn
        )
        SecurePreferences.setFingerprintHash(this, fingerprintHash)

        // Store device_secret if provided (encrypted at rest)
        response.deviceSecret?.let { secret ->
            storeDeviceSecret(secret)
        }

        // Set state to ACTIVE (enterprise QR = auto-approved, no manual approval needed)
        SecurePreferences.setDeviceState(this, SecurePreferences.STATE_ACTIVE)

        // Mark provisioning as complete — prevents re-enrollment on future boots
        SecurePreferences.setProvisioningCompleted(this, true)

        // Wipe one-time provisioning extras (token, tenant_id, policy_group_id, backend_url)
        SecurePreferences.clearProvisioningExtras(this)
        Log.i(TAG, "Provisioning extras cleared from secure storage")

        // Start MDM background services
        MDMManager.startHeartbeat(this)
        MDMManager.startPolicySync(this)

        // Start WebSocket service
        startMdmSocketService()

        // Start Geofence Service (App Pocket security)
        GeofenceService.start(this)

        // Cancel any pending retry work
        EnrollmentRetryWorker.cancel(this)

        // Broadcast success to ProvisioningStatusActivity
        sendBroadcast(Intent(ProvisioningStatusActivity.ACTION_ENROLLMENT_SUCCESS))

        // Navigate to LauncherActivity
        updateNotification("Enrollment complete — launching Dotoid")
        launchLauncher()

        stopSelf()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Wait for an active network connection, polling every [NETWORK_POLL_INTERVAL]ms
     * for up to [NETWORK_TIMEOUT_MS] before giving up.
     */
    private suspend fun waitForNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val deadline = System.currentTimeMillis() + NETWORK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isNetworkAvailable(cm)) return true
            delay(NETWORK_POLL_INTERVAL)
        }
        return false
    }

    private fun isNetworkAvailable(cm: ConnectivityManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    /**
     * Returns backend URL in priority order:
     *  1. Provisioning extra (set from QR code)
     *  2. BuildConfig.BASE_URL
     * Ensures trailing slash for Retrofit.
     */
    private fun resolveBackendUrl(): String {
        val override = SecurePreferences.getProvisioningBackendUrl(this)
        val base = if (!override.isNullOrBlank() && DeviceAdminReceiver.isValidBackendUrl(override)) {
            Log.i(TAG, "Using provisioned backend URL: $override")
            override
        } else {
            Log.i(TAG, "Using BuildConfig BASE_URL")
            BuildConfig.BASE_URL
        }
        return if (base.endsWith("/")) base else "$base/"
    }

    private fun buildRetrofitService(baseUrl: String): ConfigService {
        val logging = HttpLoggingInterceptor().apply {
            // Use HEADERS only — never log BODY to avoid leaking token
            level = HttpLoggingInterceptor.Level.HEADERS
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(ConfigService::class.java)
    }

    private fun getSerialNumber(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read serial number: ${e.message}")
            null
        }
    }

    private fun storeDeviceSecret(secret: String) {
        // Store device_secret separately in encrypted prefs using a dedicated key
        try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(this)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                this,
                "launcher_secure_prefs",
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            prefs.edit().putString("device_secret", secret).apply()
            // ⚠ secret never logged
            Log.i(TAG, "device_secret stored (redacted)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store device_secret: ${e.message}")
        }
    }

    private fun startMdmSocketService() {
        try {
            val intent = Intent(this, com.iips.launcher.mdm.MdmSocketService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MdmSocketService: ${e.message}")
        }
    }

    private fun launchLauncher() {
        val intent = Intent(this, LauncherActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
    }

    private fun scheduleRetry() {
        EnrollmentRetryWorker.schedule(this)
    }

    private fun broadcastError(message: String) {
        Log.e(TAG, "Bootstrap error: $message")
        val intent = Intent(this, ProvisioningStatusActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(ProvisioningStatusActivity.EXTRA_ERROR_MESSAGE, message)
        }
        startActivity(intent)
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Device Provisioning",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Dotoid enterprise enrollment progress"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Dotoid MDM Setup")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
