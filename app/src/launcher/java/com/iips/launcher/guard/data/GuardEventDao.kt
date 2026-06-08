package com.iips.launcher.guard.data

import androidx.room.*

@Dao
interface GuardEventDao {
    @Query("SELECT * FROM security_events WHERE is_synced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedEvents(): List<SecurityEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: SecurityEventEntity)

    @Query("UPDATE security_events SET is_synced = 1 WHERE id IN (:ids)")
    suspend fun markEventsSynced(ids: List<String>)

    @Query("DELETE FROM security_events WHERE is_synced = 1")
    suspend fun pruneSyncedEvents()
}
