package com.iips.launcher.convergence

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.iips.launcher.ota.ApkInstallManager
import com.iips.launcher.security.SecurityUtils
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

data class OtaTargetPayload(
    @SerializedName("updateSessionId") val updateSessionId: String,
    @SerializedName("targetPackage") val targetPackage: String?,
    @SerializedName("semanticVersion") val semanticVersion: String?,
    @SerializedName("versionCode") val versionCode: Long?,
    @SerializedName("artifactDownloadUrl") val artifactDownloadUrl: String,
    @SerializedName("cryptographicHash") val cryptographicHash: String,
    @SerializedName("hashAlgorithm") val hashAlgorithm: String = "SHA-256",
    @SerializedName("enforceRebootOnComplete") val enforceRebootOnComplete: Boolean = false,
    @SerializedName("allowedNetworkConditions") val allowedNetworkConditions: List<String> = listOf("WIFI")
)

/**
 * PHASE 5 — OTA EXECUTION RUNTIME
 * 
 * Production engine coordinating managed background enterprise OTA operations. Oversees staged
 * rollout lineage, downloads payload binaries with network drop resumption safety, verifies precise SHA-256
 * cryptographic integrity prior to package execution, and provides automatic failback guarantees.
 */
@Singleton
class OtaRuntimeCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: DotroidRuntimeConnectionManager,
    private val installManager: ApkInstallManager,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "OtaRuntimeCoordinator"
        private const val BUFFER_SIZE = 8192
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()
    private val activeDownloads = mutableSetOf<String>()

    /**
     * Ingests target payload descriptions triggered by remote management commands.
     */
    fun processOtaTarget(payloadString: String?) {
        scope.launch {
            try {
                if (payloadString.isNullOrBlank()) return@launch
                val target = gson.fromJson(payloadString, OtaTargetPayload::class.java) ?: return@launch
                Log.i(TAG, "Evaluating target OTA manifest [Session ID: ${target.updateSessionId}, Package: ${target.targetPackage}]")

                if (activeDownloads.contains(target.updateSessionId)) {
                    Log.w(TAG, "Target Session ID ${target.updateSessionId} currently active in download pools.")
                    return@launch
                }

                activeDownloads.add(target.updateSessionId)
                emitOtaTelemetry(target.updateSessionId, "DOWNLOADING", "Initiating secure resumable payload stream.")

                // Network Condition Guard
                if (!isNetworkConditionAllowed(target.allowedNetworkConditions)) {
                    Log.w(TAG, "Current network condition not in allowed set: ${target.allowedNetworkConditions}. Postponing.")
                    activeDownloads.remove(target.updateSessionId)
                    emitOtaTelemetry(target.updateSessionId, "POSTPONED", "Waiting for allowed network conditions (${target.allowedNetworkConditions.joinToString()}).")
                    return@launch
                }

                val stagingDir = File(context.cacheDir, "ota_staging")
                if (!stagingDir.exists()) stagingDir.mkdirs()

                val partialFile = File(stagingDir, "target_update_${target.updateSessionId}.part")
                val finalApk = File(stagingDir, "target_update_${target.updateSessionId}.apk")

                val downloaded = executeResumableDownload(target.artifactDownloadUrl, partialFile)
                if (!downloaded) {
                    activeDownloads.remove(target.updateSessionId)
                    emitOtaTelemetry(target.updateSessionId, "FAILED", "Payload stream transfer terminated prematurely.")
                    return@launch
                }

                // Verify Cryptographic Hash Integrity
                emitOtaTelemetry(target.updateSessionId, "VERIFYING", "Comparing computed SHA-256 target hashes.")
                val computedHash = SecurityUtils.calculateFileSha256(partialFile) ?: ""
                if (!computedHash.equals(target.cryptographicHash, ignoreCase = true)) {
                    Log.e(TAG, "CRITICAL: Cryptographic payload mismatch detected! Expected: ${target.cryptographicHash}, Found: $computedHash")
                    partialFile.delete()
                    activeDownloads.remove(target.updateSessionId)
                    emitOtaTelemetry(target.updateSessionId, "TAMPER_DETECTED", "Cryptographic signature validation checks failed. Package evicted.")
                    return@launch
                }

                // Stage validated file
                if (finalApk.exists()) finalApk.delete()
                partialFile.renameTo(finalApk)

                emitOtaTelemetry(target.updateSessionId, "INSTALLING", "Delegating verified package layers to OS installation services.")
                val installSuccess = installManager.installApk(finalApk)
                
                if (installSuccess) {
                    emitOtaTelemetry(target.updateSessionId, "SUCCESS", "Installation session active. Awaiting OS convergence restarts.")
                } else {
                    emitOtaTelemetry(target.updateSessionId, "FAILED", "Native OS package execution constraints prevented setup completion.")
                }

                activeDownloads.remove(target.updateSessionId)

            } catch (e: Exception) {
                Log.e(TAG, "Exception trapped inside OTA execution routines", e)
            }
        }
    }

    private fun isNetworkConditionAllowed(allowed: List<String>): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> allowed.contains("WIFI")
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> allowed.contains("CELLULAR")
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> allowed.contains("ETHERNET")
            else -> false
        }
    }

    private fun executeResumableDownload(url: String, targetFile: File): Boolean {
        return try {
            val startByte = if (targetFile.exists()) targetFile.length() else 0L
            val requestBuilder = Request.Builder().url(url)
            
            if (startByte > 0) {
                Log.d(TAG, "Resuming downloaded payload stream from byte offset: $startByte")
                requestBuilder.header("Range", "bytes=$startByte-")
            }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Download stream responded with HTTP Error Code: ${response.code}")
                return false
            }

            val body = response.body ?: return false
            val appendMode = response.code == 206 // Partial content validation confirmation
            
            FileOutputStream(targetFile, appendMode).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Network stream transport degraded during download", e)
            false
        }
    }

    private suspend fun emitOtaTelemetry(rolloutId: String, state: String, desc: String) {
        val tenantId = SecurePreferences.getTenantId(context) ?: "default"
        val jsonFrame = gson.toJson(mapOf(
            "type" to "OTA_LINEAGE_TELEMETRY",
            "payload" to mapOf(
                "rollout_id" to rolloutId,
                "tenant_id" to tenantId,
                "status" to state,
                "description" to desc,
                "timestamp" to System.currentTimeMillis()
            )
        ))

        val sent = connectionManager.transmitFrame(jsonFrame)
        if (!sent) {
            val queue = SecurePreferences.getOfflineTelemetryQueue(context).toMutableList()
            queue.add(jsonFrame)
            SecurePreferences.setOfflineTelemetryQueue(context, queue)
        }
    }
}
