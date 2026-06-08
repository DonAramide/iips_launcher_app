package com.iips.launcher.data

import androidx.room.*

@Dao
interface TrackedCoordinateDao {
    @Query("SELECT * FROM tracked_coordinates WHERE deviceId = :deviceId ORDER BY timestamp ASC")
    suspend fun getCoordinatesForDevice(deviceId: String): List<TrackedCoordinateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCoordinate(coordinate: TrackedCoordinateEntity)

    @Query("DELETE FROM tracked_coordinates WHERE deviceId = :deviceId")
    suspend fun clearForDevice(deviceId: String)
}
