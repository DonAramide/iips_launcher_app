package com.iips.launcher.policy

import android.content.Context
import com.iips.launcher.storage.SecurePreferences

object KioskLockManager {
    @Volatile
    private var isUnlocked: Boolean = false

    fun isKioskLocked(context: Context): Boolean {
        val snapshot = SecurePreferences.getDevicePolicySnapshot(context) ?: return false
        val pinEnabled = snapshot.kioskPinEnabled
        val pin = snapshot.kioskPin
        
        // Locked if PIN is enabled, PIN is not empty, and we haven't unlocked yet
        return pinEnabled && !pin.isNullOrEmpty() && !isUnlocked
    }

    fun markUnlocked() {
        isUnlocked = true
    }

    fun lockDevice() {
        isUnlocked = false
    }
}
