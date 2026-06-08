package com.iips.launcher.data

import androidx.room.*

@Dao
interface AppUsageLogDao {
    @Query("SELECT * FROM app_usage_logs ORDER BY startTime DESC")
    suspend fun getAll(): List<AppUsageLog>

    @Query("SELECT * FROM app_usage_logs WHERE packageName = :packageName ORDER BY startTime DESC")
    suspend fun getByPackageName(packageName: String): List<AppUsageLog>

    @Insert
    suspend fun insert(log: AppUsageLog)

    @Query("UPDATE app_usage_logs SET endTime = :endTime, duration = :duration WHERE id = :id")
    suspend fun updateEndTime(id: Long, endTime: Long, duration: Long)

    @Query("DELETE FROM app_usage_logs WHERE startTime < :beforeTime")
    suspend fun deleteOlderThan(beforeTime: Long)

    @Query("DELETE FROM app_usage_logs")
    suspend fun deleteAll()
}





