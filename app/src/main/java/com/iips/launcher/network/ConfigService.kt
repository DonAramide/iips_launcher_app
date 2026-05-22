package com.iips.launcher.network

import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit service for the Dotoid Quasar API specification.
 */
import com.iips.launcher.network.models.*

interface ConfigService {
    
    @GET
    suspend fun fetchConfig(@Url url: String): Response<okhttp3.ResponseBody>

    @POST("device/register")
    suspend fun enrollDevice(@Body request: EnrollmentRequest): Response<EnrollmentResponse>

    @GET("device/policy")
    suspend fun fetchPolicy(
        @Header("Authorization") authHeader: String
    ): Response<PolicyResponse>

    @GET("device-fleet/device/{deviceId}/policy")
    suspend fun getDevicePolicy(
        @Path("deviceId") deviceId: String
    ): Response<PolicyResponse>

    @POST("device/heartbeat")
    suspend fun sendHeartbeat(
        @Header("Authorization") authHeader: String,
        @Header("X-IIPS-Signature") signature: String,
        @Header("X-IIPS-Timestamp") timestamp: String,
        @Header("X-IIPS-Nonce") nonce: String,
        @Body request: HeartbeatRequest
    ): Response<okhttp3.ResponseBody>

    @POST("device/heartbeat/batch")
    suspend fun sendHeartbeatBatch(
        @Header("Authorization") authHeader: String,
        @Header("X-IIPS-Signature") signature: String,
        @Header("X-IIPS-Timestamp") timestamp: String,
        @Header("X-IIPS-Nonce") nonce: String,
        @Body requests: List<HeartbeatRequest>
    ): Response<okhttp3.ResponseBody>

    @POST("device/event")
    suspend fun sendEvent(
        @Header("Authorization") authHeader: String,
        @Body request: MdmEventRequest
    ): Response<okhttp3.ResponseBody>

    @POST("device/inventory")
    suspend fun reportInventory(
        @Header("Authorization") authHeader: String,
        @Header("X-IIPS-Signature") signature: String,
        @Body inventory: Map<String, Any>
    ): Response<okhttp3.ResponseBody>
}

