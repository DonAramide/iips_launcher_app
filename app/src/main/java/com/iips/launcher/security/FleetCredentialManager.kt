package com.iips.launcher.security

import android.content.Context
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class FleetCredentialManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun getDeviceSecret(): String? = SecurePreferences.getDeviceToken(context)
    fun getDeviceId(): String? = SecurePreferences.getDeviceId(context)
    fun hasCredentials(): Boolean = SecurePreferences.isRegistered(context)
}
