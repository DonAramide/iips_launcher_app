package com.iips.launcher.security.pds

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.iips.launcher.BuildConfig
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Debug-only authorized PDS Keystore recovery.
 * Deletes alias [PdsDeviceKeyManager.DEFAULT_DEVICE_KEY_ALIAS] only and clears
 * stale pending/bind files. Does not touch SecurePreferences / enrollment token.
 */
class PdsAuthorizedKeyReplacementReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        if (intent?.action != ACTION) return

        val status = File(context.filesDir, STATUS_FILE)
        try {
            val alias = PdsDeviceKeyManager.DEFAULT_DEVICE_KEY_ALIAS
            val keyManager = PdsDeviceKeyManager(keyAlias = alias)
            val before = keyManager.keyExists()
            val deletedOk = keyManager.deleteDeviceKeyForAuthorizedRecovery()
            val after = keyManager.keyExists()

            val pending = File(context.filesDir, PENDING_FILE)
            val bind = File(context.filesDir, BIND_FILE)
            val pendingExisted = pending.exists()
            val bindExisted = bind.exists()
            if (pendingExisted) pending.delete()
            if (bindExisted) bind.delete()

            val body = buildString {
                appendLine("alias=$alias")
                appendLine("before_exists=$before")
                appendLine("delete_ok=$deletedOk")
                appendLine("after_exists=$after")
                appendLine("pending_existed=$pendingExisted")
                appendLine("pending_exists=${pending.exists()}")
                appendLine("bind_existed=$bindExisted")
                appendLine("bind_exists=${bind.exists()}")
            }
            status.writeText(body, StandardCharsets.UTF_8)
            Log.w(TAG, "authorized key replacement complete after_exists=$after")
        } catch (t: Throwable) {
            status.writeText("error=${t.javaClass.simpleName}\n", StandardCharsets.UTF_8)
            Log.e(TAG, "authorized key replacement failed", t)
        }
    }

    companion object {
        const val ACTION = "com.iips.dotroid.action.PDS_AUTHORIZED_KEY_REPLACEMENT"
        private const val TAG = "PdsAuthKeyReplace"
        private const val STATUS_FILE = "pds_authorized_key_replacement.status"
        private const val PENDING_FILE = "pds_enrollment.pending"
        private const val BIND_FILE = "pds_key_attestation.bind"
    }
}
