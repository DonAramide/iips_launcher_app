package com.iips.launcher.commands

import android.content.Context
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NonceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun isNonceReplayed(nonce: String): Boolean {
        return SecurePreferences.isNonceReplayed(context, nonce)
    }
}
