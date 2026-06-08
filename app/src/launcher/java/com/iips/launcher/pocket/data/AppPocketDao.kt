package com.iips.launcher.pocket.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppPocketDao {
    @Query("SELECT * FROM app_pocket ORDER BY addedAt DESC")
    fun getAllAppsFlow(): Flow<List<AppPocketEntity>>

    @Query("SELECT * FROM app_pocket ORDER BY addedAt DESC")
    suspend fun getAllApps(): List<AppPocketEntity>

    @Query("SELECT * FROM app_pocket WHERE packageName = :packageName LIMIT 1")
    suspend fun getApp(packageName: String): AppPocketEntity?

    @Query("SELECT * FROM app_pocket WHERE isRequired = 1")
    fun getRequiredAppsFlow(): Flow<List<AppPocketEntity>>

    @Query("SELECT * FROM app_pocket WHERE isRequired = 1")
    suspend fun getRequiredApps(): List<AppPocketEntity>

    @Query("SELECT * FROM app_pocket WHERE status = 'INSTALLED'")
    fun getInstalledAppsFlow(): Flow<List<AppPocketEntity>>

    @Query("SELECT * FROM app_pocket WHERE status IN ('PENDING', 'AWAITING_APPROVAL', 'APPROVED', 'REJECTED')")
    fun getApprovalQueueAppsFlow(): Flow<List<AppPocketEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(app: AppPocketEntity)

    @Query("DELETE FROM app_pocket WHERE packageName = :packageName")
    suspend fun deleteApp(packageName: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudit(audit: AppPocketAuditEntity)

    @Query("SELECT * FROM app_pocket_audits WHERE synced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedAudits(): List<AppPocketAuditEntity>

    @Query("UPDATE app_pocket_audits SET synced = 1 WHERE id IN (:ids)")
    suspend fun markAuditsSynced(ids: List<Long>)
}
