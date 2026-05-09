package com.iips.launcher.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receiver that handles completion broadcasts from the PackageInstaller.
 */
@AndroidEntryPoint
class SilentInstallReceiver : BroadcastReceiver() {

    @Inject
    lateinit var rollbackManager: InstallRollbackManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SilentInstallEngine.ACTION_INSTALL_COMPLETE) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val packageName = intent.getStringExtra("package_name") ?: "unknown"
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

            when (status) {
                PackageInstaller.STATUS_SUCCESS -> {
                    Log.i("SilentInstallReceiver", "Successfully installed \$packageName")
                    rollbackManager.handleSuccess(packageName)
                }
                else -> {
                    Log.e("SilentInstallReceiver", "Failed to install \$packageName: \$message")
                    rollbackManager.handleFailure(packageName, status, message)
                }
            }
        }
    }
}
