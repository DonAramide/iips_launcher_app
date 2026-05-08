package com.iips.launcher.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.iips.launcher.config.ConfigService
import com.iips.launcher.config.InstallResultRequest
import com.iips.launcher.utils.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Worker responsible for reliably reporting the result of an APK installation.
 */
class InstallReportingWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "InstallReportingWorker"
        private val BASE_URL = com.iips.launcher.BuildConfig.BASE_URL
        
        const val KEY_APP_ID = "app_id"
        const val KEY_VERSION = "version"
        const val KEY_STATUS = "status"
        const val KEY_ERROR_MESSAGE = "error_message"
    }

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(logging)
        .addInterceptor(com.iips.launcher.config.MdmErrorInterceptor())
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .client(client)
        .build()

    private val configService = retrofit.create(ConfigService::class.java)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        
        if (!SecurePreferences.isRegistered(context)) {
            Log.w(TAG, "Device not registered, cannot report install result")
            return@withContext Result.failure()
        }

        val deviceId = SecurePreferences.getDeviceId(context) ?: return@withContext Result.failure()
        val token = SecurePreferences.getDeviceToken(context) ?: return@withContext Result.failure()

        val appId = inputData.getString(KEY_APP_ID) ?: return@withContext Result.failure()
        val version = inputData.getString(KEY_VERSION) ?: "unknown"
        val status = inputData.getString(KEY_STATUS) ?: "FAILED"
        val errorMessage = inputData.getString(KEY_ERROR_MESSAGE)

        val eventType = if (status == "SUCCESS") "INSTALL_SUCCESS" else "INSTALL_FAILURE"
        val payload = mapOf(
            "package_name" to appId,
            "version" to version,
            "error" to (errorMessage ?: "")
        )

        val request = com.iips.launcher.config.MdmEventRequest(
            type = eventType,
            payload = payload
        )

        try {
            Log.d(TAG, "Reporting install event for $appId: $eventType")
            val response = configService.sendEvent("Bearer $token", request)

            if (response.isSuccessful) {
                Log.d(TAG, "Successfully reported install event.")
                Result.success()
            } else {
                Log.e(TAG, "Failed to report install event: ${response.code()}")
                if (response.code() in 500..599 || response.code() == 429) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reporting install event", e)
            Result.retry()
        }
    }
}
