package com.iips.launcher.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.iips.launcher.ui.LauncherActivity
import com.iips.launcher.utils.DeviceController
import com.iips.launcher.utils.SecurePreferences

/**
 * Boot Receiver - Automatically starts launcher on device boot
 * This is especially useful for system apps installed to /system partition
 */
class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == Intent.ACTION_PACKAGE_REPLACED) {
            
            // Delay execution to ensure system is fully initialized
            Handler(Looper.getMainLooper()).postDelayed({
                handleBootComplete(context)
            }, 2000) // 2 second delay
        }
    }
    
    private fun handleBootComplete(context: Context) {
        try {
            // Check if Device Owner is set
            if (!DeviceAdminReceiver.isDeviceOwner(context)) {
                return // Not Device Owner, skip auto-start
            }
            
            // Auto-enable security features on boot (if previously enabled)
            if (SecurePreferences.isLockdownEnabled(context)) {
                // Security features will be enabled when LauncherActivity starts
            }
            
            // Auto-launch launcher if enabled as system app
            // This ensures launcher is always running
            val launchIntent = Intent(context, LauncherActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            
            try {
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                // Ignore if launcher cannot start (may already be running)
            }
            
            // Apply comprehensive security after boot
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                DeviceController.enableComprehensiveSecurity(context)
            }
            
        } catch (e: Exception) {
            // Ignore errors during boot (system may not be fully ready)
        }
    }
}