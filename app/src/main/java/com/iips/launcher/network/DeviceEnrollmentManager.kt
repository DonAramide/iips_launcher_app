package com.iips.launcher.network

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import com.iips.launcher.network.models.EnrollmentRequest
import com.iips.launcher.network.models.LocationInfo
import com.iips.launcher.security.SecurityUtils
import com.iips.launcher.storage.SecurePreferences
import com.iips.launcher.policy.DeviceAdminReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceEnrollmentManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configService: ConfigService
) {

    suspend fun enrollIfNeeded(enrollmentToken: String? = null, agentCode: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (SecurePreferences.isRegistered(context)) return@withContext true

        val token = enrollmentToken ?: SecurePreferences.getEnrollmentToken(context) ?: return@withContext false

        try {
            val manufacturer = Build.MANUFACTURER
            val model = Build.MODEL
            val serial = com.iips.launcher.security.SecurityUtils.getSerialNumber(context)
            val fingerprintHash = SecurityUtils.calculateFingerprintHash(context)

            val locInfo = fetchCurrentLocation(context)
            val request = EnrollmentRequest(
                enrollmentToken = token,
                manufacturer = manufacturer,
                model = model,
                serialNumber = serial,
                fingerprintHash = fingerprintHash,
                agentCode = agentCode,
                businessName = SecurePreferences.getBusinessName(context),
                latitude = locInfo?.lat,
                longitude = locInfo?.lng,
                location = locInfo
            )

            val response = configService.enrollDevice(request)
            if (response.isSuccessful) {
                response.body()?.let { res ->
                    SecurePreferences.setDeviceId(context, res.deviceId)
                    SecurePreferences.setDeviceToken(context, res.accessToken ?: "")
                    Log.i(TAG, "Enrollment successful: ${res.deviceId}")
                    return@withContext true
                }
            } else {
                Log.e(TAG, "Enrollment failed: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Enrollment error", e)
        }
        return@withContext false
    }

    companion object {
        private const val TAG = "DeviceEnrollmentManager"

        /**
         * Read, validate, and persist provisioning extras from either intent's admin extras bundle
         * or top-level intent extras.
         */
        fun extractAndPersistProvisioningExtras(context: Context, intent: Intent) {
            Log.i(TAG, "extractAndPersistProvisioningExtras — processing provisioning extras")

            // 1. Process standard PROVISIONING_ADMIN_EXTRAS_BUNDLE if present
            val extras = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                intent.getParcelableExtra<PersistableBundle>(
                    android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
                )
            } else {
                null
            }

            if (extras != null) {
                val token = extras.getString(DeviceAdminReceiver.EXTRA_ENROLLMENT_TOKEN) 
                    ?: extras.getString("enrollmentToken")
                    ?: extras.getString("enrollment_token")
                if (!token.isNullOrBlank()) {
                    SecurePreferences.setEnrollmentToken(context, token)
                    Log.i(TAG, "Stored enrollment token from bundle (redacted)")
                }

                val backendUrl = extras.getString(DeviceAdminReceiver.EXTRA_BACKEND_URL)
                    ?: extras.getString("backendUrl")
                    ?: extras.getString("backend_url")
                    ?: extras.getString("provisioning_backend_url")
                    ?: extras.getString("provisioningBackendUrl")
                if (!backendUrl.isNullOrBlank()) {
                    if (DeviceAdminReceiver.isValidBackendUrl(backendUrl)) {
                        val normalized = DeviceAdminReceiver.normalizeBackendUrl(backendUrl)
                        SecurePreferences.setProvisioningBackendUrl(context, normalized)
                        SecurePreferences.setBackendUrl(context, normalized)
                        SecurePreferences.setConfigUrl(context, normalized)
                        Log.i(TAG, "Stored normalized backendUrl from bundle to provisioning_backend_url, backend_url, and config_url: $normalized")
                    } else {
                        Log.e(TAG, "Invalid backend_url from bundle rejected: $backendUrl")
                    }
                }

                val agentCode = extras.getString("agent_code") ?: extras.getString("agentCode")
                if (!agentCode.isNullOrBlank()) {
                    SecurePreferences.setAgentCode(context, agentCode)
                    Log.i(TAG, "Stored agent_code from bundle: $agentCode")
                }

                val tenantId = extras.getString(DeviceAdminReceiver.EXTRA_TENANT_ID)
                    ?: extras.getString("tenantId")
                if (!tenantId.isNullOrBlank()) {
                    SecurePreferences.setTenantId(context, tenantId)
                    Log.d(TAG, "Stored tenant_id from bundle: $tenantId")
                }

                val policyGroupId = extras.getString(DeviceAdminReceiver.EXTRA_POLICY_GROUP_ID)
                    ?: extras.getString("policyGroupId")
                if (!policyGroupId.isNullOrBlank()) {
                    SecurePreferences.setPolicyGroupId(context, policyGroupId)
                    Log.d(TAG, "Stored policy_group_id from bundle: $policyGroupId")
                }
            } else {
                Log.d(TAG, "No PROVISIONING_ADMIN_EXTRAS_BUNDLE found in intent")
            }

            // 2. Process top-level intent extras as fallback/alternative
            val topToken = intent.getStringExtra("enrollment_token") ?: intent.getStringExtra("enrollmentToken")
            if (!topToken.isNullOrBlank()) {
                SecurePreferences.setEnrollmentToken(context, topToken)
                Log.i(TAG, "Stored top-level enrollment token (redacted)")
            }

            val topBackendUrl = intent.getStringExtra("backend_url") 
                ?: intent.getStringExtra("backendUrl")
                ?: intent.getStringExtra("provisioning_backend_url")
                ?: intent.getStringExtra("provisioningBackendUrl")
            if (!topBackendUrl.isNullOrBlank()) {
                if (DeviceAdminReceiver.isValidBackendUrl(topBackendUrl)) {
                    val normalized = DeviceAdminReceiver.normalizeBackendUrl(topBackendUrl)
                    SecurePreferences.setProvisioningBackendUrl(context, normalized)
                    SecurePreferences.setBackendUrl(context, normalized)
                    SecurePreferences.setConfigUrl(context, normalized)
                    Log.i(TAG, "Stored top-level normalized backendUrl to provisioning_backend_url, backend_url, and config_url: $normalized")
                } else {
                    Log.e(TAG, "Invalid top-level backend_url rejected: $topBackendUrl")
                }
            }

            val topAgentCode = intent.getStringExtra("agent_code") ?: intent.getStringExtra("agentCode")
            if (!topAgentCode.isNullOrBlank()) {
                SecurePreferences.setAgentCode(context, topAgentCode)
                Log.i(TAG, "Stored top-level agent_code: $topAgentCode")
            }

            val topTenantId = intent.getStringExtra("tenant_id") ?: intent.getStringExtra("tenantId")
            if (!topTenantId.isNullOrBlank()) {
                SecurePreferences.setTenantId(context, topTenantId)
                Log.d(TAG, "Stored top-level tenant_id: $topTenantId")
            }

            val topPolicyGroupId = intent.getStringExtra("policy_group_id") ?: intent.getStringExtra("policyGroupId")
            if (!topPolicyGroupId.isNullOrBlank()) {
                SecurePreferences.setPolicyGroupId(context, topPolicyGroupId)
                Log.d(TAG, "Stored top-level policy_group_id: $topPolicyGroupId")
            }
        }
    }

    private suspend fun fetchCurrentLocation(context: Context): LocationInfo? {
        try {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            val location = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            if (location != null) {
                return LocationInfo(location.latitude, location.longitude)
            }
            val lastKnown = fusedLocationClient.lastLocation.await()
            if (lastKnown != null) {
                return LocationInfo(lastKnown.latitude, lastKnown.longitude)
            }
        } catch (e: SecurityException) {
            // Ignore
        } catch (e: Exception) {
            // Ignore
        }

        // Fallback: LocationManager
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val providers = lm.getProviders(true)
            for (provider in providers) {
                val loc = lm.getLastKnownLocation(provider)
                if (loc != null) {
                    return LocationInfo(loc.latitude, loc.longitude)
                }
            }
        } catch (e: SecurityException) {
            // Ignore
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }
}
