package com.iips.launcher.network

import com.iips.launcher.network.models.*
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit service interface for all Quasar AAI Phase 6 REST endpoints.
 * Path prefix: /api/v1/manager/aai/...
 *
 * All endpoints require Bearer token authorization from the active Guard session.
 */
interface GuardAaiService {

    // ─────────────────────────────────────────────────────────────────────────
    // Dashboard
    // ─────────────────────────────────────────────────────────────────────────

    @GET("manager/aai/dashboard")
    suspend fun getDashboardSummary(
        @Header("Authorization") authHeader: String
    ): Response<AaiDashboardSummary>

    @GET("manager/aai/live-feed")
    suspend fun getLiveFeed(
        @Header("Authorization") authHeader: String,
        @Query("deviceId") deviceId: String? = null,
        @Query("limit") limit: Int = 50
    ): Response<List<AaiLiveFeedEvent>>

    // ─────────────────────────────────────────────────────────────────────────
    // Sessions
    // ─────────────────────────────────────────────────────────────────────────

    @GET("manager/aai/sessions")
    suspend fun getSessions(
        @Header("Authorization") authHeader: String,
        @Query("deviceId") deviceId: String? = null,
        @Query("packageName") packageName: String? = null,
        @Query("status") status: String? = null,
        @Query("correlationId") correlationId: String? = null,
        @Query("userId") userId: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @Query("page") page: Int = 0,
        @Query("limit") limit: Int = 50
    ): Response<AaiPagedResponse<AaiSession>>

    @GET("manager/aai/sessions/{sessionId}")
    suspend fun getSessionDetail(
        @Header("Authorization") authHeader: String,
        @Path("sessionId") sessionId: String
    ): Response<AaiSessionDetail>

    // ─────────────────────────────────────────────────────────────────────────
    // Events
    // ─────────────────────────────────────────────────────────────────────────

    @GET("manager/aai/events")
    suspend fun getEvents(
        @Header("Authorization") authHeader: String,
        @Query("deviceId") deviceId: String? = null,
        @Query("sessionId") sessionId: String? = null,
        @Query("packageName") packageName: String? = null,
        @Query("eventType") eventType: String? = null,
        @Query("correlationId") correlationId: String? = null,
        @Query("userId") userId: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @Query("page") page: Int = 0,
        @Query("limit") limit: Int = 50
    ): Response<AaiPagedResponse<AaiEvent>>

    // ─────────────────────────────────────────────────────────────────────────
    // Installed Applications
    // ─────────────────────────────────────────────────────────────────────────

    @GET("manager/aai/apps")
    suspend fun getInstalledApps(
        @Header("Authorization") authHeader: String,
        @Query("deviceId") deviceId: String? = null,
        @Query("packageName") packageName: String? = null,
        @Query("category") category: String? = null,
        @Query("riskLevel") riskLevel: String? = null,
        @Query("trustMin") trustMin: Float? = null,
        @Query("isRunning") isRunning: Boolean? = null,
        @Query("page") page: Int = 0,
        @Query("limit") limit: Int = 50
    ): Response<AaiPagedResponse<AaiInstalledApp>>

    @GET("manager/aai/apps/{packageName}")
    suspend fun getAppDetail(
        @Header("Authorization") authHeader: String,
        @Path("packageName") packageName: String,
        @Query("deviceId") deviceId: String? = null
    ): Response<AaiAppDetail>

    // ─────────────────────────────────────────────────────────────────────────
    // Rollup Analytics
    // ─────────────────────────────────────────────────────────────────────────

    @GET("manager/aai/rollups/daily")
    suspend fun getDailyRollups(
        @Header("Authorization") authHeader: String,
        @Query("deviceId") deviceId: String? = null,
        @Query("packageName") packageName: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @Query("page") page: Int = 0,
        @Query("limit") limit: Int = 30
    ): Response<AaiPagedResponse<AaiDailyRollup>>

    @GET("manager/aai/rollups/weekly")
    suspend fun getWeeklyRollups(
        @Header("Authorization") authHeader: String,
        @Query("deviceId") deviceId: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @Query("page") page: Int = 0,
        @Query("limit") limit: Int = 12
    ): Response<AaiPagedResponse<AaiWeeklyRollup>>

    @GET("manager/aai/rollups/monthly")
    suspend fun getMonthlyRollups(
        @Header("Authorization") authHeader: String,
        @Query("deviceId") deviceId: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @Query("page") page: Int = 0,
        @Query("limit") limit: Int = 12
    ): Response<AaiPagedResponse<AaiMonthlyRollup>>

    // ─────────────────────────────────────────────────────────────────────────
    // Investigation Timelines
    // ─────────────────────────────────────────────────────────────────────────

    @GET("manager/aai/investigations/{correlationId}")
    suspend fun getInvestigationTimeline(
        @Header("Authorization") authHeader: String,
        @Path("correlationId") correlationId: String
    ): Response<AaiInvestigationTimeline>

    @GET("manager/aai/investigations")
    suspend fun searchInvestigations(
        @Header("Authorization") authHeader: String,
        @Query("correlationId") correlationId: String? = null,
        @Query("sessionId") sessionId: String? = null,
        @Query("deviceId") deviceId: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @Query("page") page: Int = 0,
        @Query("limit") limit: Int = 50
    ): Response<AaiPagedResponse<AaiInvestigationTimeline>>

    // ─────────────────────────────────────────────────────────────────────────
    // Compliance
    // ─────────────────────────────────────────────────────────────────────────

    @GET("manager/aai/compliance/findings")
    suspend fun getComplianceFindings(
        @Header("Authorization") authHeader: String,
        @Query("deviceId") deviceId: String? = null,
        @Query("severity") severity: String? = null,
        @Query("status") status: String? = null,
        @Query("findingType") findingType: String? = null,
        @Query("page") page: Int = 0,
        @Query("limit") limit: Int = 50
    ): Response<AaiPagedResponse<AaiComplianceFinding>>

    @GET("manager/aai/compliance/violations")
    suspend fun getPolicyViolations(
        @Header("Authorization") authHeader: String,
        @Query("deviceId") deviceId: String? = null,
        @Query("severity") severity: String? = null,
        @Query("violationType") violationType: String? = null,
        @Query("page") page: Int = 0,
        @Query("limit") limit: Int = 50
    ): Response<AaiPagedResponse<AaiPolicyViolation>>

    // ─────────────────────────────────────────────────────────────────────────
    // Notifications
    // ─────────────────────────────────────────────────────────────────────────

    @GET("manager/aai/notifications")
    suspend fun getNotifications(
        @Header("Authorization") authHeader: String,
        @Query("isRead") isRead: Boolean? = null,
        @Query("notificationType") notificationType: String? = null,
        @Query("page") page: Int = 0,
        @Query("limit") limit: Int = 50
    ): Response<AaiPagedResponse<AaiNotification>>

    @POST("manager/aai/notifications/{notificationId}/read")
    suspend fun markNotificationRead(
        @Header("Authorization") authHeader: String,
        @Path("notificationId") notificationId: String
    ): Response<GuardCommonApiResponse>

    // ─────────────────────────────────────────────────────────────────────────
    // Export Jobs
    // ─────────────────────────────────────────────────────────────────────────

    @POST("manager/aai/exports")
    suspend fun createExportJob(
        @Header("Authorization") authHeader: String,
        @Body request: AaiExportRequest
    ): Response<AaiExportJob>

    @GET("manager/aai/exports/{jobId}")
    suspend fun getExportJobStatus(
        @Header("Authorization") authHeader: String,
        @Path("jobId") jobId: String
    ): Response<AaiExportJob>

    @GET("manager/aai/exports")
    suspend fun getExportJobs(
        @Header("Authorization") authHeader: String,
        @Query("page") page: Int = 0,
        @Query("limit") limit: Int = 20
    ): Response<AaiPagedResponse<AaiExportJob>>
}
