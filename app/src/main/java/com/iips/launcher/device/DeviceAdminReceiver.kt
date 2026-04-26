package com.iips.launcher.device

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class DeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // Device admin enabled
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // Device admin disabled
    }

    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, DeviceAdminReceiver::class.java)
        }

        fun isDeviceOwner(context: Context): Boolean {
            return try {
                val devicePolicyManager =
                    context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val packageName = context.packageName
                val result = devicePolicyManager.isDeviceOwnerApp(packageName)
                val isAdminActive = try {
                    devicePolicyManager.isAdminActive(getComponentName(context))
                } catch (e: Exception) { false }
                android.util.Log.d("DeviceAdminReceiver", 
                    "isDeviceOwner check: packageName=$packageName, " +
                    "isDeviceOwnerApp=$result, isAdminActive=$isAdminActive")
                result
            } catch (e: Exception) {
                android.util.Log.e("DeviceAdminReceiver", 
                    "isDeviceOwner exception: ${e.message}", e)
                false
            }
        }
    }
}





