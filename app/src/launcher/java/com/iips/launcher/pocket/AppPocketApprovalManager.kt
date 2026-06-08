package com.iips.launcher.pocket

import com.iips.launcher.data.AppDatabase
import com.iips.launcher.pocket.data.AppPocketEntity
import com.iips.launcher.pocket.data.AppPocketAuditEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPocketApprovalManager @Inject constructor(
    private val database: AppDatabase
) {
    suspend fun requestApproval(packageName: String, requesterId: String): Boolean {
        val dao = database.appPocketDao()
        val app = dao.getApp(packageName) ?: return false
        if (app.status != "PENDING") return false

        val updated = app.copy(status = "AWAITING_APPROVAL")
        dao.insertApp(updated)
        dao.insertAudit(
            AppPocketAuditEntity(
                packageName = packageName,
                appName = app.appName,
                versionName = app.versionName,
                versionCode = app.versionCode,
                actionType = "SUBMITTED_APPROVAL",
                userId = requesterId
            )
        )
        return true
    }

    suspend fun approveApp(packageName: String, managerId: String): Boolean {
        val dao = database.appPocketDao()
        val app = dao.getApp(packageName) ?: return false
        if (app.status != "AWAITING_APPROVAL") return false

        val updated = app.copy(status = "APPROVED")
        dao.insertApp(updated)
        dao.insertAudit(
            AppPocketAuditEntity(
                packageName = packageName,
                appName = app.appName,
                versionName = app.versionName,
                versionCode = app.versionCode,
                actionType = "APPROVED",
                userId = managerId
            )
        )
        return true
    }

    suspend fun rejectApp(packageName: String, managerId: String): Boolean {
        val dao = database.appPocketDao()
        val app = dao.getApp(packageName) ?: return false
        if (app.status != "AWAITING_APPROVAL") return false

        val updated = app.copy(status = "REJECTED")
        dao.insertApp(updated)
        dao.insertAudit(
            AppPocketAuditEntity(
                packageName = packageName,
                appName = app.appName,
                versionName = app.versionName,
                versionCode = app.versionCode,
                actionType = "REJECTED",
                userId = managerId
            )
        )
        return true
    }
}
