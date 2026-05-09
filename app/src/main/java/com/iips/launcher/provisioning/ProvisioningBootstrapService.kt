package com.iips.launcher.provisioning

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
import com.iips.launcher.network.ConfigService
import com.iips.launcher.network.models.EnrollmentRequest
import com.iips.launcher.network.models.EnrollmentResponse
import com.iips.launcher.ui.LauncherActivity
import com.iips.launcher.ui.ProvisioningStatusActivity
import com.iips.launcher.storage.SecurePreferences
import com.iips.launcher.security.SecurityUtils
import com.iips.launcher.policy.DeviceAdminReceiver
import com.iips.launcher.policy.GeofenceService
import com.iips.launcher.workers.EnrollmentRetryWorker
import com.iips.launcher.workers.TelemetryWorker
import com.iips.launcher.workers.PolicySyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import retrofit2.Response
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.pow

@AndroidEntryPoint
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
        const val EXTRA_IS_RETRY = "is_retry"
    }

    @Inject
    lateinit var configService: ConfigService

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("Enrolling device into Dotoid MDM…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isRetry = intent?.getBooleanExtra(EXTRA_IS_RETRY, false) ?: false
        scope.launch { runBootstrap(isRetry) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runBootstrap(isRetry: Boolean) {
        try {
            if (!DeviceAdminReceiver.isDeviceOwner(this)) {
                broadcastError("Device is not set as Device Owner. Factory reset and re-provision.")
                stopSelf()
                return
            }

            if (SecurePreferences.isProvisioningCompleted(this) && SecurePreferences.isRegistered(this)) {
                launchLauncher()
                stopSelf()
                return
            }

            updateNotification("Waiting for network connectivity…")
            if (!waitForNetwork()) {
                broadcastError("No network connection. Connect to Wi-Fi and retry.")
                scheduleRetry()
                stopSelf()
                return
            }

            val enrollmentToken = SecurePreferences.getEnrollmentToken(this)
            if (enrollmentToken.isNullOrBlank()) {
                broadcastError("Missing enrollment token. Re-provision device.")
                stopSelf()
                return
            }

            val tenantId = SecurePreferences.getTenantId(this)
            val policyGroupId = SecurePreferences.getPolicyGroupId(this)

            updateNotification("Enrolling into Quasar MDM…")
            val fingerprintHash = SecurityUtils.calculateFingerprintHash(this)
            val serial = getSerialNumber()

            val request = EnrollmentRequest(
                enrollmentToken = enrollmentToken,
                tenantId = tenantId,
                policyGroupId = policyGroupId,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                androidVersion = Build.VERSION.RELEASE,
                serialNumber = serial,
                fingerprint = Build.FINGERPRINT,
                fingerprintHash = fingerprintHash,
                appVersion = BuildConfig.VERSION_NAME
            )

            val response = enrollWithRetry(request)

            if (response != null && response.isSuccessful) {
                onEnrollmentSuccess(response.body()!!, fingerprintHash)
            } else {
                val code = response?.code() ?: -1
                val isTerminal = code in 400..499
                broadcastError(if (isTerminal) "Invalid enrollment token (HTTP $code). Re-provision device." 
                               else "Enrollment failed (HTTP $code). Check network and retry.")
                if (!isTerminal) scheduleRetry()
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during bootstrap", e)
            broadcastError("Unexpected error: \${e.message}")
            scheduleRetry()
            stopSelf()
        }
    }

    private suspend fun enrollWithRetry(request: EnrollmentRequest): Response<EnrollmentResponse>? {
        var lastResponse: Response<EnrollmentResponse>? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                val response = configService.enrollDevice(request)
                if (response.isSuccessful) return response
                if (response.code() in 400..499) return response
                lastResponse = response
            } catch (e: Exception) {
                Log.w(TAG, "Enrollment attempt $attempt threw: \${e.message}")
            }

            if (attempt < MAX_RETRIES) {
                delay(calculateBackoff(attempt))
            }
        }
        return lastResponse
    }

    private fun calculateBackoff(attempt: Int): Long {
        val expo = (BASE_BACKOFF_MS * 2.0.pow(attempt - 1)).toLong()
        val capped = min(expo, MAX_BACKOFF_MS)
        return capped + (capped * 0.2 * Math.random()).toLong()
    }

    private fun onEnrollmentSuccess(response: EnrollmentResponse, fingerprintHash: String) {
        SecurePreferences.setDeviceId(this, response.deviceId)
        SecurePreferences.setDeviceToken(this, response.accessToken ?: "")
        SecurePreferences.setFingerprintHash(this, fingerprintHash)
        SecurePreferences.setDeviceState(this, SecurePreferences.STATE_ACTIVE)
        SecurePreferences.setProvisioningCompleted(this, true)
        SecurePreferences.clearProvisioningExtras(this)

        TelemetryWorker.schedule(this)
        PolicySyncWorker.schedule(this)
        com.iips.launcher.workers.ProvisioningBootstrapWorker.start(this)
        
        try {
            val intent = Intent(this, com.iips.launcher.network.MdmSocketService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) { }

        GeofenceService.start(this)
        EnrollmentRetryWorker.cancel(this)
        sendBroadcast(Intent(ProvisioningStatusActivity.ACTION_ENROLLMENT_SUCCESS))
        launchLauncher()
        stopSelf()
    }

    private suspend fun waitForNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val deadline = System.currentTimeMillis() + NETWORK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val network = cm.activeNetwork ?: continue
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return true
            delay(NETWORK_POLL_INTERVAL)
        }
        return false
    }

    private fun getSerialNumber(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial() else Build.SERIAL
        } catch (e: Exception) { null }
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
        val intent = Intent(this, ProvisioningStatusActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(ProvisioningStatusActivity.EXTRA_ERROR_MESSAGE, message)
        }
        startActivity(intent)
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIF_CHANNEL_ID, "Device Provisioning", NotificationManager.IMPORTANCE_LOW)
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
