package com.iips.launcher.security.pds

import android.content.Context
import android.util.Log
import com.iips.launcher.BuildConfig
import com.iips.launcher.storage.SecurePreferences
import java.io.File

/**
 * Debug-only enrollment credential handoff (Stage 2.5D.2).
 *
 * Host decrypts DPAPI → restrictive temp file → ADB pipe into app-private
 * `files/pds_import/enrollment_token.pending`. On next process start this
 * importer:
 * - reads the pending plaintext once
 * - stores it in EncryptedSharedPreferences
 * - seeds the non-secret Phase 1D tenant UUID from [BuildConfig.PDS_PHASE1D_TENANT_ID]
 * - deletes the pending file
 * - writes a presence-only status marker (never the token)
 *
 * Not registered as an exported component. Absent from release source sets.
 */
object PdsDebugCredentialImport {
    private const val TAG = "PdsDebugCredImport"
    const val IMPORT_DIR = "pds_import"
    const val PENDING_TOKEN_FILE = "enrollment_token.pending"
    const val STATUS_FILE = "import_status.txt"

    @JvmStatic
    fun importIfPending(context: Context) {
        if (!BuildConfig.PDS_DEBUG_CREDENTIAL_IMPORT) {
            return
        }
        val dir = File(context.filesDir, IMPORT_DIR)
        val pending = File(dir, PENDING_TOKEN_FILE)
        if (!pending.isFile) {
            ensureTenantSeed(context)
            writeStatus(context, pendingExisted = false)
            return
        }

        try {
            val tokenChars = pending.readText(Charsets.UTF_8).trim()
            if (tokenChars.isEmpty()) {
                Log.w(TAG, "Pending enrollment token file empty; deleting")
                secureDelete(pending)
                ensureTenantSeed(context)
                writeStatus(context, pendingExisted = true, tokenImported = false)
                return
            }
            SecurePreferences.setEnrollmentToken(context, tokenChars)
            ensureTenantSeed(context)
            // Also pin integration backend URL for loopback enrollments.
            val backend = BuildConfig.BASE_URL
            if (backend.isNotBlank()) {
                SecurePreferences.setProvisioningBackendUrl(context, backend)
                SecurePreferences.setBackendUrl(context, backend)
            }
            secureDelete(pending)
            val present = SecurePreferences.hasEnrollmentToken(context)
            val tenantOk = PdsTenantIdentity.isValidTenantUuid(
                SecurePreferences.getExplicitTenantId(context)
            )
            writeStatus(
                context,
                pendingExisted = true,
                tokenImported = present,
                tenantUuidPresent = tenantOk,
                stagingCleared = !pending.exists()
            )
            Log.i(
                TAG,
                "Debug enrollment handoff consumed (token_present=$present tenant_uuid_present=$tenantOk)"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Debug enrollment handoff failed: ${e.javaClass.simpleName}")
            try {
                secureDelete(pending)
            } catch (_: Exception) {
            }
            writeStatus(context, pendingExisted = true, tokenImported = false, error = e.javaClass.simpleName)
        }
    }

    private fun ensureTenantSeed(context: Context) {
        val configured = BuildConfig.PDS_PHASE1D_TENANT_ID.trim()
        if (!PdsTenantIdentity.isValidTenantUuid(configured)) {
            return
        }
        val existing = SecurePreferences.getExplicitTenantId(context)
        if (!PdsTenantIdentity.isValidTenantUuid(existing)) {
            SecurePreferences.setTenantId(context, configured)
        }
    }

    private fun writeStatus(
        context: Context,
        pendingExisted: Boolean,
        tokenImported: Boolean = SecurePreferences.hasEnrollmentToken(context),
        tenantUuidPresent: Boolean = PdsTenantIdentity.isValidTenantUuid(
            SecurePreferences.getExplicitTenantId(context)
        ),
        stagingCleared: Boolean = true,
        error: String? = null
    ) {
        val dir = File(context.filesDir, IMPORT_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val status = File(dir, STATUS_FILE)
        // tenant_uuid is non-secret Phase 1D config; never write enrollment token here.
        val explicitTenant = SecurePreferences.getExplicitTenantId(context)?.trim().orEmpty()
        val lines = buildString {
            append("token_present=").append(tokenImported).append('\n')
            append("tenant_uuid_present=").append(tenantUuidPresent).append('\n')
            append("tenant_uuid=").append(explicitTenant).append('\n')
            append("pending_existed=").append(pendingExisted).append('\n')
            append("staging_cleared=").append(stagingCleared).append('\n')
            if (error != null) {
                append("error=").append(error).append('\n')
            }
        }
        status.writeText(lines, Charsets.UTF_8)
    }

    private fun secureDelete(file: File) {
        if (!file.exists()) return
        try {
            val len = file.length().coerceAtLeast(1L).toInt().coerceAtMost(1_048_576)
            file.writeBytes(ByteArray(len))
        } catch (_: Exception) {
        }
        file.delete()
    }
}
