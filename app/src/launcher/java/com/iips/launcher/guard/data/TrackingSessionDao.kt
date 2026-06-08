package com.iips.launcher.guard.data

import androidx.room.*

@Dao
interface TrackingSessionDao {
    @Query("SELECT * FROM tracking_sessions WHERE is_active = 1 LIMIT 1")
    suspend fun getActiveSession(): TrackingSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: TrackingSessionEntity)

    @Query("UPDATE tracking_sessions SET is_active = 0, end_time = :endTime WHERE session_id = :sessionId")
    suspend fun closeSession(sessionId: String, endTime: Long)
}
