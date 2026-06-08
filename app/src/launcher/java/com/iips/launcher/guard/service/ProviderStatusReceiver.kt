package com.iips.launcher.guard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log

class ProviderStatusReceiver(private val onStatusChanged: (Boolean) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
            context?.let { ctx ->
                val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                Log.d("ProviderStatusReceiver", "GPS status changed. Enabled: $isGpsEnabled")
                onStatusChanged(isGpsEnabled)
            }
        }
    }
}
