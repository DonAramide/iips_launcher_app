package com.iips.launcher.network

import com.iips.launcher.network.models.*
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface GuardAuthService {
    @POST("manager/login")
    suspend fun login(@Body request: GuardLoginRequest): Response<GuardLoginResponse>

    @POST("manager/refresh")
    suspend fun refreshToken(@Body request: TokenRefreshRequest): Response<GuardLoginResponse>

    @POST("manager/device/register")
    suspend fun registerManagerDevice(
        @Header("Authorization") authHeader: String,
        @Body request: ManagerDeviceRegistrationRequest
    ): Response<Unit>
}
