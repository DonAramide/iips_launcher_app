package com.iips.launcher.telemetry.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TelemetryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TelemetryEntity)

    @Query("SELECT * FROM pending_telemetry ORDER BY id ASC LIMIT 50")
    suspend fun getPendingBatch(): List<TelemetryEntity>

    @Query("DELETE FROM pending_telemetry WHERE id IN (:ids)")
    suspend fun deleteBatch(ids: List<Long>)
}
