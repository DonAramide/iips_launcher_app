package com.iips.launcher.policy

import android.content.Context
import android.location.Location
import android.util.Log
import com.iips.launcher.network.ConfigService
import com.iips.launcher.network.models.MdmEventRequest
import com.iips.launcher.storage.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

@Singleton
class GeofenceManager @Inject constructor(
    private val configService: ConfigService
) {
    private val TAG = "GeofenceManager"
    private val MAX_SPEED_MPS = 200.0 
    private var lastLocation: Location? = null

    fun shouldLockDevice(context: Context, location: Location): Boolean {
        val snapshot = SecurePreferences.getDevicePolicySnapshot(context) ?: return false
        val zones = snapshot.geofenceRules
        if (zones.isEmpty()) return false

        for (zone in zones) {
            val distance = calculateDistance(location.latitude, location.longitude, zone.lat, zone.lng)
            if (distance <= zone.radius_m) return false 
        }
        return true
    }

    fun getClosestZone(context: Context, location: Location): com.iips.launcher.network.models.GeofenceRule? {
        val snapshot = SecurePreferences.getDevicePolicySnapshot(context) ?: return null
        val zones = snapshot.geofenceRules
        if (zones.isEmpty()) return null

        var closestZone: com.iips.launcher.network.models.GeofenceRule? = null
        var minDistance = Double.MAX_VALUE

        for (zone in zones) {
            val distance = calculateDistance(location.latitude, location.longitude, zone.lat, zone.lng)
            if (distance < minDistance) {
                minDistance = distance
                closestZone = zone
            }
        }
        return closestZone
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371e3 
        val φ1 = lat1 * PI / 180
        val φ2 = lat2 * PI / 180
        val Δφ = (lat2 - lat1) * PI / 180
        val Δλ = (lon2 - lon1) * PI / 180
        val a = sin(Δφ / 2).pow(2) + cos(φ1) * cos(φ2) * sin(Δλ / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    suspend fun reportStatus(context: Context, location: Location, isInside: Boolean) = withContext(Dispatchers.IO) {
        val token = SecurePreferences.getDeviceToken(context) ?: return@withContext
        val payload = mapOf(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracy" to location.accuracy,
            "event" to (if (isInside) "GEOFENCE_INSIDE" else "GEOFENCE_OUTSIDE")
        )
        val request = MdmEventRequest(type = "GEOFENCE_STATUS", payload = payload)
        try {
            configService.sendEvent("Bearer $token", request)
        } catch (e: Exception) {
            Log.e(TAG, "Error reporting geofence status", e)
        }
    }

    fun isLocationSpoofed(context: Context, location: Location): Boolean {
        val isMock = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
        if (isMock) return true

        lastLocation?.let { last ->
            val distance = calculateDistance(last.latitude, last.longitude, location.latitude, location.longitude)
            val timeDeltaSeconds = (location.time - last.time) / 1000.0
            if (timeDeltaSeconds > 0 && (distance / timeDeltaSeconds) > MAX_SPEED_MPS) return true
        }
        lastLocation = location
        return false
    }
}
