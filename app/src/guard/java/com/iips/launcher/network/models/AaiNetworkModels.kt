package com.iips.launcher.network.models

import com.google.gson.annotations.SerializedName

// ─────────────────────────────────────────────────────────────────────────────
// Generic Paged Response Wrapper
// ─────────────────────────────────────────────────────────────────────────────

data class AaiPagedResponse<T>(
    val data: List<T>,
    val total: Int,
    val page: Int,
    val limit: Int,
    val hasMore: Boolean
)

// ─────────────────────────────────────────────────────────────────────────────
// AAI Dashboard
// ─────────────────────────────────────────────────────────────────────────────

data class AaiDashboardSummary(
    val activeDevices: Int,
    val totalSessions: Int,
    val activeSessions: Int,
    val activeApplications: Int,
    val totalFocusTimeSeconds: Long,
    val complianceViolations: Int,
    val policyViolations: Int,
    val pendingAlerts: Int,
    val lastUpdatedAt: String?
)

data class AaiLiveFeedEvent(
    val eventId: String,
    val deviceId: String,
    val deviceName: String?,
    val packageName: String,
    val appName: String?,
    val eventType: String,        // APP_FOREGROUND, APP_BACKGROUND, SESSION_START, SESSION_END, COMPLIANCE_BREACH
    val timestamp: String,
    val sessionId: String?,
    val correlationId: String?,
    val riskLevel: String?        // LOW, MEDIUM, HIGH, CRITICAL
)

// ─────────────────────────────────────────────────────────────────────────────
// Sessions
// ─────────────────────────────────────────────────────────────────────────────

data class AaiSession(
    val sessionId: String,
    val deviceId: String,
    val deviceName: String?,
    val packageName: String,
    val appName: String?,
    val startTime: String,
    val endTime: String?,
    val durationSeconds: Long?,
    val focusTimeSeconds: Long?,
    val idleTimeSeconds: Long?,
    val backgroundTimeSeconds: Long?,
    val status: String,           // ACTIVE, COMPLETED, ABORTED
    val correlationId: String?,
    val tenantId: String?,
    val branchId: String?
)

data class AaiSessionDetail(
    val session: AaiSession,
    val events: List<AaiSessionEvent>,
    val relatedSessions: List<AaiSession>
)

data class AaiSessionEvent(
    val eventId: String,
    val eventType: String,
    val timestamp: String,
    val payload: Map<String, Any>?
)

// ─────────────────────────────────────────────────────────────────────────────
// Events
// ─────────────────────────────────────────────────────────────────────────────

data class AaiEvent(
    val eventId: String,
    val sessionId: String?,
    val deviceId: String,
    val packageName: String,
    val appName: String?,
    val eventType: String,
    val eventSource: String?,
    val confidence: String?,
    val timestamp: String,
    val correlationId: String?,
    val clockOffsetMs: Long?,
    val payload: Map<String, Any>?
)

// ─────────────────────────────────────────────────────────────────────────────
// Installed Applications
// ─────────────────────────────────────────────────────────────────────────────

data class AaiInstalledApp(
    val id: String,
    val deviceId: String,
    val deviceName: String?,
    val packageName: String,
    val appName: String,
    val versionName: String?,
    val versionCode: Long?,
    val category: String?,        // PRODUCTIVITY, SOCIAL, FINANCE, GAMING, UTILITY, OTHER
    val trustScore: Float?,       // 0.0 - 1.0
    val riskLevel: String?,       // LOW, MEDIUM, HIGH, CRITICAL
    val isSystemApp: Boolean,
    val isRunning: Boolean,
    val lastSeenAt: String?,
    val installedAt: String?,
    val permissions: List<String>?
)

data class AaiAppDetail(
    val app: AaiInstalledApp,
    val versionHistory: List<AaiAppVersion>,
    val recentSessions: List<AaiSession>,
    val complianceStatus: String?
)

data class AaiAppVersion(
    val versionName: String,
    val versionCode: Long,
    val installedAt: String,
    val removedAt: String?
)

// ─────────────────────────────────────────────────────────────────────────────
// Rollup Analytics
// ─────────────────────────────────────────────────────────────────────────────

data class AaiDailyRollup(
    val date: String,
    val deviceId: String?,
    val packageName: String?,
    val totalSessions: Int,
    val totalFocusTimeSeconds: Long,
    val totalIdleTimeSeconds: Long,
    val totalBackgroundTimeSeconds: Long,
    val totalEvents: Int,
    val uniqueApps: Int,
    val complianceBreaches: Int
)

data class AaiWeeklyRollup(
    val weekStart: String,
    val weekEnd: String,
    val deviceId: String?,
    val totalSessions: Int,
    val totalFocusTimeSeconds: Long,
    val avgSessionDurationSeconds: Long,
    val uniqueApps: Int,
    val complianceBreaches: Int
)

data class AaiMonthlyRollup(
    val month: String,
    val deviceId: String?,
    val totalSessions: Int,
    val totalFocusTimeSeconds: Long,
    val avgDailyUsageSeconds: Long,
    val uniqueApps: Int,
    val complianceBreaches: Int,
    val topApps: List<AaiTopApp>
)

data class AaiTopApp(
    val packageName: String,
    val appName: String?,
    val totalFocusTimeSeconds: Long,
    val sessionCount: Int
)

// ─────────────────────────────────────────────────────────────────────────────
// Investigation Timelines
// ─────────────────────────────────────────────────────────────────────────────

data class AaiInvestigationTimeline(
    val correlationId: String,
    val events: List<AaiTimelineEvent>,
    val relatedSessions: List<AaiSession>,
    val deviceTimeline: List<AaiDeviceTimelineEntry>,
    val summary: AaiInvestigationSummary?
)

data class AaiTimelineEvent(
    val eventId: String,
    val timestamp: String,
    val eventType: String,
    val deviceId: String,
    val packageName: String,
    val severity: String?,
    val description: String?
)

data class AaiDeviceTimelineEntry(
    val timestamp: String,
    val deviceId: String,
    val deviceName: String?,
    val state: String,
    val packageName: String?
)

data class AaiInvestigationSummary(
    val firstEvent: String?,
    val lastEvent: String?,
    val totalDevices: Int,
    val totalEvents: Int,
    val riskLevel: String?
)

// ─────────────────────────────────────────────────────────────────────────────
// Compliance
// ─────────────────────────────────────────────────────────────────────────────

data class AaiComplianceFinding(
    val findingId: String,
    val deviceId: String,
    val deviceName: String?,
    val packageName: String?,
    val appName: String?,
    val findingType: String,       // BLACKLISTED_APP, RESTRICTED_APP, POLICY_VIOLATION, RISK_THRESHOLD
    val severity: String,          // LOW, MEDIUM, HIGH, CRITICAL
    val status: String,            // OPEN, ACKNOWLEDGED, RESOLVED, DISMISSED
    val detectedAt: String,
    val resolvedAt: String?,
    val description: String?,
    val recommendation: String?
)

data class AaiPolicyViolation(
    val violationId: String,
    val policyId: String,
    val policyName: String?,
    val deviceId: String,
    val deviceName: String?,
    val packageName: String?,
    val violationType: String,
    val severity: String,
    val detectedAt: String,
    val description: String?
)

// ─────────────────────────────────────────────────────────────────────────────
// Notifications
// ─────────────────────────────────────────────────────────────────────────────

data class AaiNotification(
    val notificationId: String,
    val deviceId: String?,
    val deviceName: String?,
    val title: String,
    val message: String,
    val notificationType: String,  // ALERT, COMPLIANCE, SYSTEM, INFO
    val severity: String,
    val isRead: Boolean,
    val createdAt: String,
    val readAt: String?
)

// ─────────────────────────────────────────────────────────────────────────────
// Export Jobs
// ─────────────────────────────────────────────────────────────────────────────

data class AaiExportRequest(
    val exportType: String,        // CSV, JSON, PDF
    val dataType: String,          // SESSIONS, EVENTS, APPS, COMPLIANCE, ROLLUP_DAILY, ROLLUP_WEEKLY, ROLLUP_MONTHLY
    val filters: AaiExportFilter?,
    val dateFrom: String?,
    val dateTo: String?
)

data class AaiExportFilter(
    val deviceIds: List<String>?,
    val packageNames: List<String>?,
    val tenantId: String?,
    val branchId: String?
)

data class AaiExportJob(
    val jobId: String,
    val status: String,            // PENDING, PROCESSING, COMPLETED, FAILED
    val exportType: String,
    val dataType: String,
    val downloadUrl: String?,
    val createdAt: String,
    val completedAt: String?,
    val errorMessage: String?,
    val fileSizeBytes: Long?
)

// ─────────────────────────────────────────────────────────────────────────────
// Filters (query parameters model)
// ─────────────────────────────────────────────────────────────────────────────

data class AaiFilterParams(
    val tenantId: String? = null,
    val branchId: String? = null,
    val deviceId: String? = null,
    val userId: String? = null,
    val packageName: String? = null,
    val category: String? = null,
    val riskLevel: String? = null,
    val trustMin: Float? = null,
    val correlationId: String? = null,
    val sessionId: String? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val page: Int = 0,
    val limit: Int = 50
)

// ─────────────────────────────────────────────────────────────────────────────
// WebSocket / SSE stream frames
// ─────────────────────────────────────────────────────────────────────────────

data class AaiStreamFrame(
    val type: String,             // LIVE_EVENT, SESSION_START, SESSION_END, COMPLIANCE_BREACH, HEARTBEAT
    val payload: AaiLiveFeedEvent?,
    val timestamp: String?
)
