package com.iips.launcher.aai.filter

import android.content.Context
import android.content.SharedPreferences
import com.iips.launcher.network.models.AaiFilterParams
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages AAI filter state and persists the last-used filters across sessions.
 * Filters are stored in a dedicated SharedPreferences file for the Guard console.
 */
@Singleton
class AaiFilterPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME       = "aai_filter_prefs"
        private const val KEY_TENANT_ID    = "filter_tenant_id"
        private const val KEY_BRANCH_ID    = "filter_branch_id"
        private const val KEY_DEVICE_ID    = "filter_device_id"
        private const val KEY_USER_ID      = "filter_user_id"       // R2 addition
        private const val KEY_PACKAGE_NAME = "filter_package_name"
        private const val KEY_CATEGORY     = "filter_category"
        private const val KEY_RISK_LEVEL   = "filter_risk_level"
        private const val KEY_TRUST_MIN    = "filter_trust_min"     // R2 addition
        private const val KEY_CORRELATION_ID = "filter_correlation_id"
        private const val KEY_SESSION_ID   = "filter_session_id"
        private const val KEY_DATE_FROM    = "filter_date_from"
        private const val KEY_DATE_TO      = "filter_date_to"
        private const val KEY_PAGE_LIMIT   = "filter_page_limit"
        private const val NO_TRUST_VALUE   = -1f
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveFilters(params: AaiFilterParams) {
        prefs.edit()
            .putString(KEY_TENANT_ID, params.tenantId)
            .putString(KEY_BRANCH_ID, params.branchId)
            .putString(KEY_DEVICE_ID, params.deviceId)
            .putString(KEY_USER_ID, params.userId)
            .putString(KEY_PACKAGE_NAME, params.packageName)
            .putString(KEY_CATEGORY, params.category)
            .putString(KEY_RISK_LEVEL, params.riskLevel)
            .putFloat(KEY_TRUST_MIN, params.trustMin ?: NO_TRUST_VALUE)
            .putString(KEY_CORRELATION_ID, params.correlationId)
            .putString(KEY_SESSION_ID, params.sessionId)
            .putString(KEY_DATE_FROM, params.dateFrom)
            .putString(KEY_DATE_TO, params.dateTo)
            .putInt(KEY_PAGE_LIMIT, params.limit)
            .apply()
    }

    fun loadFilters(): AaiFilterParams {
        val rawTrust = prefs.getFloat(KEY_TRUST_MIN, NO_TRUST_VALUE)
        return AaiFilterParams(
            tenantId      = prefs.getString(KEY_TENANT_ID, null),
            branchId      = prefs.getString(KEY_BRANCH_ID, null),
            deviceId      = prefs.getString(KEY_DEVICE_ID, null),
            userId        = prefs.getString(KEY_USER_ID, null),
            packageName   = prefs.getString(KEY_PACKAGE_NAME, null),
            category      = prefs.getString(KEY_CATEGORY, null),
            riskLevel     = prefs.getString(KEY_RISK_LEVEL, null),
            trustMin      = if (rawTrust == NO_TRUST_VALUE) null else rawTrust,
            correlationId = prefs.getString(KEY_CORRELATION_ID, null),
            sessionId     = prefs.getString(KEY_SESSION_ID, null),
            dateFrom      = prefs.getString(KEY_DATE_FROM, null),
            dateTo        = prefs.getString(KEY_DATE_TO, null),
            limit         = prefs.getInt(KEY_PAGE_LIMIT, 50),
            page          = 0
        )
    }

    fun clearFilters() {
        prefs.edit().clear().apply()
    }

    fun hasActiveFilters(): Boolean {
        val f = loadFilters()
        return f.tenantId != null || f.branchId != null || f.deviceId != null ||
               f.userId != null || f.packageName != null || f.category != null ||
               f.riskLevel != null || f.trustMin != null ||
               f.correlationId != null || f.sessionId != null ||
               f.dateFrom != null || f.dateTo != null
    }
}

