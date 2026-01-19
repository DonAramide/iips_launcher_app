package com.iips.launcher.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.iips.launcher.R

object SecurePreferences {
    private const val PREFS_NAME = "launcher_secure_prefs"

    private fun getEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getAdminPassword(context: Context): String {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString("admin_password", context.getString(R.string.default_admin_password)) ?: 
               context.getString(R.string.default_admin_password)
    }

    fun setAdminPassword(context: Context, password: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString("admin_password", password).apply()
    }

    fun isLockdownEnabled(context: Context): Boolean {
        val prefs = getEncryptedPrefs(context)
        return prefs.getBoolean("lockdown_enabled", true)
    }

    fun setLockdownEnabled(context: Context, enabled: Boolean) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putBoolean("lockdown_enabled", enabled).apply()
    }

    fun isImmersiveModeEnabled(context: Context): Boolean {
        val prefs = getEncryptedPrefs(context)
        return prefs.getBoolean("immersive_mode", true)
    }

    fun setImmersiveModeEnabled(context: Context, enabled: Boolean) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putBoolean("immersive_mode", enabled).apply()
    }

    fun isFactoryResetProtectionEnabled(context: Context): Boolean {
        val prefs = getEncryptedPrefs(context)
        return prefs.getBoolean("factory_reset_protection", true) // Enabled by default
    }

    fun setFactoryResetProtectionEnabled(context: Context, enabled: Boolean) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putBoolean("factory_reset_protection", enabled).apply()
    }
}

