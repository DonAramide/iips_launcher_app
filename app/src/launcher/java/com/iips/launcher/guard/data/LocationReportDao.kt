package com.iips.launcher.guard.data

import androidx.room.*

@Dao
interface LocationReportDao {
    @Query("SELECT * FROM location_reports WHERE is_synced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedLocations(): List<LocationReportEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(location: LocationReportEntity)

    @Query("UPDATE location_reports SET is_synced = 1 WHERE id IN (:ids)")
    suspend fun markLocationsSynced(ids: List<String>)

    @Query("DELETE FROM location_reports WHERE is_synced = 1")
    suspend fun pruneSyncedLocations()
}
