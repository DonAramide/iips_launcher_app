package com.iips.launcher.auth

import android.content.Context
import android.os.Build
import com.iips.launcher.data.ManagerProfileDao
import com.iips.launcher.data.ManagerProfileEntity
import com.iips.launcher.network.GuardAuthService
import com.iips.launcher.network.models.*
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GuardAuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authService: GuardAuthService,
    private val profileDao: ManagerProfileDao
) {

    suspend fun login(request: GuardLoginRequest): Result<ManagerProfileEntity> = withContext(Dispatchers.IO) {
        try {
            val response = authService.login(request)
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    // Store tokens in SecurePreferences (Encrypted)
                    SecurePreferences.setGuardAuthToken(context, body.token)
                    SecurePreferences.setGuardRefreshToken(context, body.refreshToken)

                    // Store profile in Room database
                    val profile = ManagerProfileEntity(
                        id = body.manager.id,
                        name = body.manager.name,
                        email = body.manager.email,
                        phone = body.manager.phone,
                        role = body.manager.role
                    )
                    profileDao.insertProfile(profile)
                    Result.success(profile)
                } else {
                    Result.failure(Exception("Response body is empty"))
                }
            } else {
                Result.failure(Exception("Login failed: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshToken(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val refreshToken = SecurePreferences.getGuardRefreshToken(context)
                ?: return@withContext Result.failure(Exception("No refresh token stored"))

            val response = authService.refreshToken(TokenRefreshRequest(refreshToken))
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    SecurePreferences.setGuardAuthToken(context, body.token)
                    SecurePreferences.setGuardRefreshToken(context, body.refreshToken)
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Refresh failed: ${response.code()}"))
                }
            } else {
                Result.failure(Exception("Refresh failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun registerDevice(fcmToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profile = profileDao.getProfile()
                ?: return@withContext Result.failure(Exception("No active manager profile"))

            val token = SecurePreferences.getGuardAuthToken(context)
                ?: return@withContext Result.failure(Exception("No authorization token"))

            val deviceId = SecurePreferences.getDeviceId(context) ?: java.util.UUID.randomUUID().toString()
            if (SecurePreferences.getDeviceId(context) == null) {
                SecurePreferences.setDeviceId(context, deviceId)
            }

            val request = ManagerDeviceRegistrationRequest(
                managerId = profile.id,
                deviceId = deviceId,
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
                osVersion = "Android ${Build.VERSION.RELEASE}",
                fcmToken = fcmToken
            )

            val authHeader = "Bearer $token"
            val response = authService.registerManagerDevice(authHeader, request)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Device registration failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreSession(): Result<ManagerProfileEntity> = withContext(Dispatchers.IO) {
        val token = SecurePreferences.getGuardAuthToken(context)
        val profile = profileDao.getProfile()
        if (token != null && profile != null) {
            Result.success(profile)
        } else {
            Result.failure(Exception("No active session"))
        }
    }

    suspend fun logout(): Unit = withContext(Dispatchers.IO) {
        SecurePreferences.clearGuardTokens(context)
        profileDao.clearProfile()
    }
}
