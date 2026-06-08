package com.iips.launcher.network

import com.iips.launcher.network.models.*
import retrofit2.Response
import retrofit2.http.*

interface GuardPairingService {
    @POST("manager/pair")
    suspend fun submitPairingToken(
        @Header("Authorization") authHeader: String,
        @Body request: GuardPairingRequest
    ): Response<GuardPairingResponse>

    @POST("manager/paired-devices/{deviceId}/status")
    suspend fun updatePairingStatus(
        @Header("Authorization") authHeader: String,
        @Path("deviceId") deviceId: String,
        @Body request: PairingStatusRequest
    ): Response<PairingStatusResponse>

    @GET("manager/paired-devices")
    suspend fun getPairedDevices(
        @Header("Authorization") authHeader: String
    ): Response<List<GuardPairingResponse>>

    @GET("manager/paired-devices/{deviceId}")
    suspend fun getDeviceDetails(
        @Header("Authorization") authHeader: String,
        @Path("deviceId") deviceId: String
    ): Response<GuardPairingResponse>

    @GET("manager/devices/summary")
    suspend fun getDeviceSummary(
        @Header("Authorization") authHeader: String
    ): Response<GuardDeviceSummaryResponse>
}
