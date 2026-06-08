package com.iips.launcher.network

import com.iips.launcher.network.models.PairingStatusResponse
import com.iips.launcher.network.models.PairingTokenResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface LauncherPairingService {
    @POST("devices/{deviceId}/pairing-token")
    suspend fun getPairingToken(
        @Path("deviceId") deviceId: String,
        @Header("Authorization") authHeader: String
    ): Response<PairingTokenResponse>

    @GET("devices/{deviceId}/pairing-status")
    suspend fun getPairingStatus(
        @Path("deviceId") deviceId: String,
        @Header("Authorization") authHeader: String
    ): Response<PairingStatusResponse>
}
