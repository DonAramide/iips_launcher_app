package com.iips.launcher.policy

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import com.iips.launcher.storage.SecurePreferences
import com.iips.launcher.provisioning.ProvisioningBootstrapService

/**
 * Dotroid Device Policy Controller (DPC) receiver.
 *
 * Handles Android Enterprise QR provisioning via:
 *  - [onProfileProvisioningComplete] — triggered when Android finishes installing and
 *    granting Device Owner after a QR code scan. Extracts provisioning extras and
 *    launches [ProvisioningBootstrapService] for automatic Quasar enrollment.
 *  - [onEnabled] — fallback for cases where the app is enabled as Device Owner via
 *    non-QR methods (ADB, DPC app, etc.).
 *
 * Security contract:
 *  - The raw enrollment_token is NEVER logged.
 *  - backend_url is validated (must start with "https://") before storing.
 *  - After successful enrollment (handled in [ProvisioningBootstrapService]),
 *    provisioning extras are wiped from [SecurePreferences].
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "DotroidDPC"

        // Provisioning extras keys expected inside PROVISIONING_ADMIN_EXTRAS_BUNDLE
        const val EXTRA_BACKEND_URL       = "backend_url"
        const val EXTRA_ENROLLMENT_TOKEN  = "enrollment_token"
        const val EXTRA_TENANT_ID         = "tenant_id"
        const val EXTRA_POLICY_GROUP_ID   = "policy_group_id"

        fun getComponentName(context: Context): ComponentName =
            ComponentName(context, com.iips.launcher.policy.DeviceAdminReceiver::class.java)

        fun isDeviceOwner(context: Context): Boolean {
            return try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val result = dpm.isDeviceOwnerApp(context.packageName)
                val isAdminActive = try {
                    dpm.isAdminActive(getComponentName(context))
                } catch (e: Exception) { false }
                Log.d(TAG, "isDeviceOwner: packageName=${context.packageName}, " +
                           "isDeviceOwnerApp=$result, isAdminActive=$isAdminActive")
                result
            } catch (e: Exception) {
                Log.e(TAG, "isDeviceOwner exception: ${e.message}", e)
                false
            }
        }

        /**
         * Validate that a backend URL is safe to use:
         *  - Must start with https://
         *  - Must be longer than 10 characters
         */
        fun isValidBackendUrl(url: String): Boolean =
            url.startsWith("https://") && url.length > 10
    }

    // ── Provisioning complete ────────────────────────────────────────────────

    /**
     * Called by Android when Device Owner provisioning is complete (API 23+).
     * This is the primary entry-point for zero-touch / QR enrollment.
     *
     * [intent] contains [DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE]
     * with the custom extras embedded in the provisioning QR code.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "onProfileProvisioningComplete — starting enterprise bootstrap")

        // Extract admin extras bundle from provisioning intent
        val extras: PersistableBundle? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intent.getParcelableExtra(
                android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
            )
        } else {
            null
        }

        if (extras != null) {
            persistProvisioningExtras(context, extras)
        } else {
            Log.w(TAG, "onProfileProvisioningComplete — no admin extras bundle found; " +
                       "will rely on previously stored enrollment token if present")
        }

        // ── Enterprise initialization ────────────────────────────────────────
        // 1. Set Dotroid as the default home launcher automatically
        com.iips.launcher.policy.DeviceController.setDefaultLauncher(context)

        // 2. Apply initial security restrictions common to all managed devices
        com.iips.launcher.policy.DeviceController.enableComprehensiveSecurity(context)

        // Launch bootstrap service to perform the actual enrollment
        launchBootstrapService(context)
    }

    // ── Admin enabled / disabled ─────────────────────────────────────────────

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "onEnabled — Device admin enabled")

        // If we already have a stored provisioning token but haven't completed
        // provisioning yet (e.g. app was killed mid-flow), restart bootstrap.
        if (!SecurePreferences.isProvisioningCompleted(context) &&
            SecurePreferences.getEnrollmentToken(context) != null) {
            Log.i(TAG, "onEnabled — Detected unfinished provisioning, resuming bootstrap")
            launchBootstrapService(context)
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "onDisabled — Device admin was disabled")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Read, validate, and persist provisioning extras to [SecurePreferences].
     * Token is stored but NEVER logged.
     */
    private fun persistProvisioningExtras(context: Context, extras: PersistableBundle) {
        // enrollment_token — required
        val token = extras.getString(EXTRA_ENROLLMENT_TOKEN)
        if (token.isNullOrBlank()) {
            Log.e(TAG, "persistProvisioningExtras — enrollment_token is missing from extras")
        } else {
            SecurePreferences.setEnrollmentToken(context, token)
            // ⚠ Do NOT log 'token' here
            Log.i(TAG, "persistProvisioningExtras — enrollment_token stored (redacted)")
        }

        // backend_url — optional override
        val backendUrl = extras.getString(EXTRA_BACKEND_URL)
        if (!backendUrl.isNullOrBlank()) {
            if (isValidBackendUrl(backendUrl)) {
                SecurePreferences.setProvisioningBackendUrl(context, backendUrl)
                Log.i(TAG, "persistProvisioningExtras — backend_url set to: $backendUrl")
            } else {
                Log.e(TAG, "persistProvisioningExtras — invalid backend_url rejected: $backendUrl")
            }
        }

        // tenant_id — optional
        val tenantId = extras.getString(EXTRA_TENANT_ID)
        if (!tenantId.isNullOrBlank()) {
            SecurePreferences.setTenantId(context, tenantId)
            Log.d(TAG, "persistProvisioningExtras — tenant_id: $tenantId")
        }

        // policy_group_id — optional
        val policyGroupId = extras.getString(EXTRA_POLICY_GROUP_ID)
        if (!policyGroupId.isNullOrBlank()) {
            SecurePreferences.setPolicyGroupId(context, policyGroupId)
            Log.d(TAG, "persistProvisioningExtras — policy_group_id: $policyGroupId")
        }
    }

    /** Start [ProvisioningBootstrapService] as a foreground service. */
    private fun launchBootstrapService(context: Context) {
        val serviceIntent = Intent(context, ProvisioningBootstrapService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "ProvisioningBootstrapService started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ProvisioningBootstrapService: ${e.message}", e)
        }
    }
}
