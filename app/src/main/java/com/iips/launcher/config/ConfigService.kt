package com.iips.launcher.config

import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit service for the Dotoid Quasar API specification.
 */
interface ConfigService {
    
    /**
     * Fetch configuration from a dynamic URL (Legacy helper).
     */
    @GET
    suspend fun fetchConfig(@Url url: String): Response<okhttp3.ResponseBody>

    /**
     * Register device with the Quasar server (§3.1) — legacy path.
     * Auth: None. Uses enrollment_token in body.
     */
    @POST("device/register")
    suspend fun registerDevice(@Body request: RegistrationRequest): Response<RegistrationResponse>

    /**
     * Enterprise enrollment — primary path per Quasar spec.
     * Called automatically by [com.iips.launcher.device.ProvisioningBootstrapService]
     * after Android Enterprise QR provisioning completes.
     * Auth: None. Uses enrollment_token in body.
     */
    @POST("devices/enroll")
    suspend fun enrollDevice(@Body request: EnrollmentRequest): Response<EnrollmentResponse>

    /**
     * Fetch unified active policy for the device (§3.2).
     * Auth: Bearer JWT.
     */
    @GET("device/policy")
    suspend fun fetchPolicy(
        @Header("Authorization") authHeader: String
    ): Response<PolicyResponse>

    /**
     * Send heartbeat telemetry to the server (§3.3).
     * Auth: Bearer JWT.
     */
    @POST("device/heartbeat")
    suspend fun sendHeartbeat(
        @Header("Authorization") authHeader: String,
        @Body request: HeartbeatRequest
    ): Response<okhttp3.ResponseBody>

    /**
     * Report a client-side event (§3.4).
     * Auth: Bearer JWT.
     */
    @POST("device/event")
    suspend fun sendEvent(
        @Header("Authorization") authHeader: String,
        @Body request: MdmEventRequest
    ): Response<okhttp3.ResponseBody>
}

