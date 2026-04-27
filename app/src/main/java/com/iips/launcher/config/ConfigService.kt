package com.iips.launcher.config

import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit service for fetching remote configuration and MDM tasks.
 */
interface ConfigService {
    
    /**
     * Fetch configuration from a dynamic URL.
     */
    @GET
    suspend fun fetchConfig(@Url url: String): Response<ConfigResponse>

    /**
     * Register device with the MDM server.
     */
    @POST("api/v1/devices/register")
    suspend fun registerDevice(@Body request: RegistrationRequest): Response<RegistrationResponse>

    /**
     * Send heartbeat telemetry to the MDM server.
     */
    @POST("api/v1/devices/heartbeat")
    suspend fun sendHeartbeat(
        @Header("Authorization") token: String, // Bearer {device_token}
        @Body request: HeartbeatRequest
    ): Response<Unit>

    /**
     * Fetch geofence configuration from the backend.
     */
    @GET("device/geofence-config")
    suspend fun fetchGeofenceConfig(
        @Query("device_id") deviceId: String
    ): Response<GeofenceConfigResponse>

    /**
     * Send periodic status updates to the backend.
     */
    @POST("device/status")
    suspend fun sendGeofenceStatus(
        @Body request: GeofenceStatusRequest
    ): Response<Unit>

    /**
     * Submit geofence proposals for admin approval.
     */
    @POST("device/geofence-proposal")
    suspend fun submitGeofenceProposal(
        @Body request: GeofenceProposalRequest
    ): Response<Unit>
}
