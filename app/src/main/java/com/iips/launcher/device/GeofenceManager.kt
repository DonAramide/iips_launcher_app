package com.iips.launcher.device

import android.content.Context
import android.location.Location
import android.util.Log
import com.iips.launcher.config.*
import com.iips.launcher.utils.HardwareProvider
import com.iips.launcher.utils.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/**
 * Manages Geofencing logic, distance calculation, and status reporting.
 */
object GeofenceManager {
    private const val TAG = "GeofenceManager"
    private val BASE_URL = com.iips.launcher.BuildConfig.BASE_URL
    private const val TOLERANCE_BUFFER = 1.2f

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(logging)
        .addInterceptor(MdmErrorInterceptor())
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .client(client)
        .build()

    private val service = retrofit.create(ConfigService::class.java)

    /**
     * Evaluate the current location against geofence zones in the unified policy.
     * Returns true if the device should be LOCKED (outside all zones).
     */
    fun shouldLockDevice(context: Context, location: Location): Boolean {
        val snapshot = SecurePreferences.getDevicePolicySnapshot(context) ?: return false
        
        // ONLY enforce "approved" zones (or all rules in new spec)
        val zones = snapshot.geofenceRules
        if (zones.isEmpty()) {
            Log.d(TAG, "No geofence rules found in snapshot. Skipping lock.")
            return false
        }

        // Check each zone
        for (zone in zones) {
            val distance = calculateDistance(
                location.latitude, location.longitude,
                zone.lat, zone.lng
            )
            
            val effectiveRadius = zone.radius_m * TOLERANCE_BUFFER
            
            Log.d(TAG, "Zone ${zone.name}: Distance=$distance, Radius=$effectiveRadius")
            
            if (distance <= effectiveRadius) {
                // Inside at least one zone
                return false 
            }
        }

        // Outside all zones
        return true
    }

    /**
     * Haversine formula to calculate distance between two points in meters.
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371e3 // Earth radius in meters
        val φ1 = lat1 * PI / 180
        val φ2 = lat2 * PI / 180
        val Δφ = (lat2 - lat1) * PI / 180
        val Δλ = (lon2 - lon1) * PI / 180

        val a = sin(Δφ / 2).pow(2) +
                cos(φ1) * cos(φ2) *
                sin(Δλ / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return r * c
    }

    /**
     * Report current status to the backend via the event endpoint.
     */
    suspend fun reportStatus(context: Context, location: Location, isInside: Boolean) = withContext(Dispatchers.IO) {
        val token = SecurePreferences.getDeviceToken(context) ?: return@withContext
        
        val payload = mapOf(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracy" to location.accuracy,
            "event" to (if (isInside) "GEOFENCE_INSIDE" else "GEOFENCE_OUTSIDE")
        )

        val request = com.iips.launcher.config.MdmEventRequest(
            type = "GEOFENCE_STATUS",
            payload = payload
        )

        try {
            Log.d(TAG, "Reporting geofence status event: $request")
            val response = service.sendEvent("Bearer $token", request)
            if (response.isSuccessful) {
                Log.d(TAG, "Geofence status reported successfully")
            } else {
                Log.e(TAG, "Failed to report geofence status: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reporting geofence status", e)
        }
    }
    
    private var lastLocation: Location? = null
    private const val MAX_SPEED_MPS = 200.0 // 200 m/s (approx 720 km/h) - unlikely for typical usage

    /**
     * Anti-spoofing checks.
     */
    fun isLocationSpoofed(context: Context, location: Location): Boolean {
        // 1. Basic Mock Provider check
        val isMock = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
        if (isMock) {
            Log.w(TAG, "Spoofing detected: Mock Provider used")
            return true
        }

        // 2. Developer Mode check
        if (isDeveloperModeEnabled(context)) {
            Log.w(TAG, "Spoofing detected: Developer Mode is enabled")
            return true
        }

        // 3. Unrealistic Jump check
        lastLocation?.let { last ->
            val distance = calculateDistance(last.latitude, last.longitude, location.latitude, location.longitude)
            val timeDeltaSeconds = (location.time - last.time) / 1000.0
            
            if (timeDeltaSeconds > 0) {
                val speed = distance / timeDeltaSeconds
                if (speed > MAX_SPEED_MPS) {
                    Log.w(TAG, "Spoofing detected: Unrealistic jump ($speed m/s)")
                    return true
                }
            }
        }
        
        lastLocation = location
        return false
    }

    private fun isDeveloperModeEnabled(context: Context): Boolean {
        return android.provider.Settings.Global.getInt(
            context.contentResolver,
            android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
        ) != 0
    }

}
