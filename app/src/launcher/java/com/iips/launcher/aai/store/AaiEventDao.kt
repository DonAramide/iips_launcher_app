package com.iips.launcher.aai.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AaiEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: AaiEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<AaiEventEntity>)

    @Query("SELECT * FROM aai_events WHERE uploaded = 0 ORDER BY device_timestamp ASC LIMIT :limit")
    suspend fun getUnuploadedEvents(limit: Int): List<AaiEventEntity>

    @Query("UPDATE aai_events SET uploaded = 1 WHERE event_id IN (:eventIds)")
    suspend fun markAsUploaded(eventIds: List<String>)

    @Query("DELETE FROM aai_events WHERE uploaded = 1 AND device_timestamp < :retentionThreshold")
    suspend fun deleteUploadedEventsOlderThan(retentionThreshold: Long)
}
