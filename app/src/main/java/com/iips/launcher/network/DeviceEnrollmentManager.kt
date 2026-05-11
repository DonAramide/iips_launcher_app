package com.iips.launcher.network

import android.content.Context
import android.os.Build
import android.util.Log
import com.iips.launcher.network.models.EnrollmentRequest
import com.iips.launcher.security.SecurityUtils
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceEnrollmentManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configService: ConfigService
) {
    private val TAG = "DeviceEnrollmentManager"

    suspend fun enrollIfNeeded(enrollmentToken: String? = null, agentCode: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (SecurePreferences.isRegistered(context)) return@withContext true

        val token = enrollmentToken ?: SecurePreferences.getEnrollmentToken(context) ?: return@withContext false

        try {
            val manufacturer = Build.MANUFACTURER
            val model = Build.MODEL
            val serial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { Build.getSerial() } catch (e: Exception) { "UNKNOWN" }
            } else {
                Build.SERIAL
            }
            val fingerprintHash = SecurityUtils.calculateFingerprintHash(context)

            val request = EnrollmentRequest(
                enrollmentToken = token,
                tenantId = SecurePreferences.getTenantId(context),
                policyGroupId = null,
                manufacturer = manufacturer,
                model = model,
                androidVersion = Build.VERSION.RELEASE,
                serialNumber = serial,
                fingerprint = Build.FINGERPRINT,
                fingerprintHash = fingerprintHash,
                appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0",
                agentCode = agentCode
            )

            val response = configService.enrollDevice(request)
            if (response.isSuccessful) {
                response.body()?.let { res ->
                    SecurePreferences.setDeviceId(context, res.deviceId)
                    SecurePreferences.setDeviceToken(context, res.accessToken ?: "")
                    Log.i(TAG, "Enrollment successful: \${res.deviceId}")
                    return@withContext true
                }
            } else {
                Log.e(TAG, "Enrollment failed: \${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Enrollment error", e)
        }
        return@withContext false
    }
}
