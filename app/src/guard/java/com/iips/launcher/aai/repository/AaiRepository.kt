package com.iips.launcher.aai.repository

import android.content.Context
import com.iips.launcher.aai.cache.AaiMemoryCache
import com.iips.launcher.aai.cache.AaiMemoryCache.Companion.KEY_APPS
import com.iips.launcher.aai.cache.AaiMemoryCache.Companion.KEY_COMPLIANCE
import com.iips.launcher.aai.cache.AaiMemoryCache.Companion.KEY_DASHBOARD
import com.iips.launcher.aai.cache.AaiMemoryCache.Companion.KEY_NOTIFICATIONS
import com.iips.launcher.aai.cache.AaiMemoryCache.Companion.KEY_SESSIONS
import com.iips.launcher.aai.cache.AaiMemoryCache.Companion.KEY_VIOLATIONS
import com.iips.launcher.network.GuardAaiService
import com.iips.launcher.network.models.*
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for all AAI data operations.
 *
 * Wraps [GuardAaiService] calls, handles auth header injection, and maps
 * HTTP responses to [Result] sealed outcomes for clean ViewModel consumption.
 *
 * ## Caching
 * A 30-second in-memory TTL cache ([AaiMemoryCache]) sits in front of the five most
 * frequently-accessed list endpoints: Dashboard, Apps, Sessions, Compliance, Notifications.
 * Pass [forceRefresh]=true to bypass the cache on any call.
 *
 * The cache is automatically invalidated:
 * - After a successful export-job creation (call [invalidateCacheOnExport])
 * - When a significant WebSocket event arrives (call [invalidateCacheOnStreamEvent])
 * - When [invalidateAll] is called (e.g. on logout or global filter change)
 *
 * No networking or business logic may live in the UI layer — all calls route through
 * this repository only.
 */
@Singleton
class AaiRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aaiService: GuardAaiService,
    private val cache: AaiMemoryCache
) {
    private val auth: String
        get() = "Bearer ${SecurePreferences.getGuardAuthToken(context) ?: ""}"

    // ─────────────────────────────────────────────────────────────────────────
    // Cache management helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Call after a successful export-job creation to force fresh data on next fetch. */
    suspend fun invalidateCacheOnExport() {
        cache.invalidate(KEY_SESSIONS, KEY_APPS, KEY_COMPLIANCE, KEY_VIOLATIONS)
    }

    /**
     * Call when a significant WebSocket event (SESSION_START/END, COMPLIANCE_BREACH) is
     * received to ensure Dashboard and related lists reflect live state.
     */
    suspend fun invalidateCacheOnStreamEvent(eventType: String) {
        when (eventType) {
            "SESSION_START", "SESSION_END" -> cache.invalidate(KEY_DASHBOARD, KEY_SESSIONS)
            "COMPLIANCE_BREACH" -> cache.invalidate(KEY_DASHBOARD, KEY_COMPLIANCE, KEY_VIOLATIONS)
            "APP_INSTALL", "APP_UNINSTALL" -> cache.invalidate(KEY_APPS)
            else -> cache.invalidate(KEY_DASHBOARD)
        }
    }

    /** Wipes the entire cache — call on logout or global filter change. */
    suspend fun invalidateAll() = cache.invalidateAll()

    // ─────────────────────────────────────────────────────────────────────────
    // Dashboard
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getDashboardSummary(forceRefresh: Boolean = false): Result<AaiDashboardSummary> {
        if (!forceRefresh) {
            cache.get<AaiDashboardSummary>(KEY_DASHBOARD)?.let { return Result.success(it) }
        }
        return safeCall { aaiService.getDashboardSummary(auth) }.also { result ->
            result.getOrNull()?.let { cache.put(KEY_DASHBOARD, it) }
        }
    }

    suspend fun getLiveFeed(deviceId: String? = null, limit: Int = 50): Result<List<AaiLiveFeedEvent>> =
        safeCall { aaiService.getLiveFeed(auth, deviceId, limit) }

    // ─────────────────────────────────────────────────────────────────────────
    // Sessions
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getSessions(
        deviceId: String? = null,
        packageName: String? = null,
        status: String? = null,
        correlationId: String? = null,
        userId: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        page: Int = 0,
        limit: Int = 50,
        forceRefresh: Boolean = false
    ): Result<AaiPagedResponse<AaiSession>> {
        val cacheKey = "$KEY_SESSIONS:$deviceId:$packageName:$status:$correlationId:$userId:$dateFrom:$dateTo:$page"
        if (!forceRefresh) {
            cache.get<AaiPagedResponse<AaiSession>>(cacheKey)?.let { return Result.success(it) }
        }
        return safeCall {
            aaiService.getSessions(auth, deviceId, packageName, status, correlationId, userId, dateFrom, dateTo, page, limit)
        }.also { result ->
            result.getOrNull()?.let { cache.put(cacheKey, it) }
        }
    }

    suspend fun getSessionDetail(sessionId: String): Result<AaiSessionDetail> = safeCall {
        aaiService.getSessionDetail(auth, sessionId)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Events
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getEvents(
        deviceId: String? = null,
        sessionId: String? = null,
        packageName: String? = null,
        eventType: String? = null,
        correlationId: String? = null,
        userId: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        page: Int = 0,
        limit: Int = 50
    ): Result<AaiPagedResponse<AaiEvent>> = safeCall {
        aaiService.getEvents(auth, deviceId, sessionId, packageName, eventType, correlationId, userId, dateFrom, dateTo, page, limit)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Applications
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getInstalledApps(
        deviceId: String? = null,
        packageName: String? = null,
        category: String? = null,
        riskLevel: String? = null,
        trustMin: Float? = null,
        isRunning: Boolean? = null,
        page: Int = 0,
        limit: Int = 50,
        forceRefresh: Boolean = false
    ): Result<AaiPagedResponse<AaiInstalledApp>> {
        val cacheKey = "$KEY_APPS:$deviceId:$packageName:$category:$riskLevel:$trustMin:$page"
        if (!forceRefresh) {
            cache.get<AaiPagedResponse<AaiInstalledApp>>(cacheKey)?.let { return Result.success(it) }
        }
        return safeCall {
            aaiService.getInstalledApps(auth, deviceId, packageName, category, riskLevel, trustMin, isRunning, page, limit)
        }.also { result ->
            result.getOrNull()?.let { cache.put(cacheKey, it) }
        }
    }

    suspend fun getAppDetail(packageName: String, deviceId: String? = null): Result<AaiAppDetail> = safeCall {
        aaiService.getAppDetail(auth, packageName, deviceId)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rollups
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getDailyRollups(
        deviceId: String? = null,
        packageName: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        page: Int = 0
    ): Result<AaiPagedResponse<AaiDailyRollup>> = safeCall {
        aaiService.getDailyRollups(auth, deviceId, packageName, dateFrom, dateTo, page)
    }

    suspend fun getWeeklyRollups(
        deviceId: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        page: Int = 0
    ): Result<AaiPagedResponse<AaiWeeklyRollup>> = safeCall {
        aaiService.getWeeklyRollups(auth, deviceId, dateFrom, dateTo, page)
    }

    suspend fun getMonthlyRollups(
        deviceId: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        page: Int = 0
    ): Result<AaiPagedResponse<AaiMonthlyRollup>> = safeCall {
        aaiService.getMonthlyRollups(auth, deviceId, dateFrom, dateTo, page)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Investigation
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getInvestigationTimeline(correlationId: String): Result<AaiInvestigationTimeline> = safeCall {
        aaiService.getInvestigationTimeline(auth, correlationId)
    }

    suspend fun searchInvestigations(
        correlationId: String? = null,
        sessionId: String? = null,
        deviceId: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        page: Int = 0
    ): Result<AaiPagedResponse<AaiInvestigationTimeline>> = safeCall {
        aaiService.searchInvestigations(auth, correlationId, sessionId, deviceId, dateFrom, dateTo, page)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Compliance
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getComplianceFindings(
        deviceId: String? = null,
        severity: String? = null,
        status: String? = null,
        findingType: String? = null,
        page: Int = 0,
        forceRefresh: Boolean = false
    ): Result<AaiPagedResponse<AaiComplianceFinding>> {
        val cacheKey = "$KEY_COMPLIANCE:$deviceId:$severity:$status:$page"
        if (!forceRefresh) {
            cache.get<AaiPagedResponse<AaiComplianceFinding>>(cacheKey)?.let { return Result.success(it) }
        }
        return safeCall {
            aaiService.getComplianceFindings(auth, deviceId, severity, status, findingType, page)
        }.also { result ->
            result.getOrNull()?.let { cache.put(cacheKey, it) }
        }
    }

    suspend fun getPolicyViolations(
        deviceId: String? = null,
        severity: String? = null,
        violationType: String? = null,
        page: Int = 0,
        forceRefresh: Boolean = false
    ): Result<AaiPagedResponse<AaiPolicyViolation>> {
        val cacheKey = "$KEY_VIOLATIONS:$deviceId:$severity:$page"
        if (!forceRefresh) {
            cache.get<AaiPagedResponse<AaiPolicyViolation>>(cacheKey)?.let { return Result.success(it) }
        }
        return safeCall {
            aaiService.getPolicyViolations(auth, deviceId, severity, violationType, page)
        }.also { result ->
            result.getOrNull()?.let { cache.put(cacheKey, it) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notifications
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getNotifications(
        isRead: Boolean? = null,
        notificationType: String? = null,
        page: Int = 0,
        forceRefresh: Boolean = false
    ): Result<AaiPagedResponse<AaiNotification>> {
        val cacheKey = "$KEY_NOTIFICATIONS:$isRead:$page"
        if (!forceRefresh) {
            cache.get<AaiPagedResponse<AaiNotification>>(cacheKey)?.let { return Result.success(it) }
        }
        return safeCall {
            aaiService.getNotifications(auth, isRead, notificationType, page)
        }.also { result ->
            result.getOrNull()?.let { cache.put(cacheKey, it) }
        }
    }

    suspend fun markNotificationRead(notificationId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = aaiService.markNotificationRead(auth, notificationId)
            if (response.isSuccessful) {
                // Invalidate notification cache so badge count updates
                cache.invalidate(KEY_NOTIFICATIONS)
                Result.success(Unit)
            } else Result.failure(Exception("Failed [${response.code()}]"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Export Jobs
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun createExportJob(request: AaiExportRequest): Result<AaiExportJob> {
        return safeCall { aaiService.createExportJob(auth, request) }.also { result ->
            if (result.isSuccess) {
                // Invalidate related caches — the data being exported may just have changed
                invalidateCacheOnExport()
            }
        }
    }

    suspend fun getExportJobStatus(jobId: String): Result<AaiExportJob> = safeCall {
        aaiService.getExportJobStatus(auth, jobId)
    }

    suspend fun getExportJobs(page: Int = 0): Result<AaiPagedResponse<AaiExportJob>> = safeCall {
        aaiService.getExportJobs(auth, page)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Safe-call helper — always runs on Dispatchers.IO
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun <T> safeCall(call: suspend () -> retrofit2.Response<T>): Result<T> =
        withContext(Dispatchers.IO) {
            try {
                val response = call()
                if (response.isSuccessful && response.body() != null) {
                    Result.success(response.body()!!)
                } else {
                    Result.failure(Exception("HTTP ${response.code()}: ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
