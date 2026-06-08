package com.iips.launcher.guard.security

import android.location.Location
import android.os.Build

object MockLocationDetector {
    fun isMockLocation(location: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
    }
}
