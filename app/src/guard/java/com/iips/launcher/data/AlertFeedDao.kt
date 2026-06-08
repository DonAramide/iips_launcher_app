package com.iips.launcher.data

import androidx.room.*

@Dao
interface AlertFeedDao {
    @Query("SELECT * FROM alert_feed ORDER BY timestamp DESC")
    suspend fun getAllAlerts(): List<AlertFeedEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlert(alert: AlertFeedEntity)

    @Query("UPDATE alert_feed SET isAcknowledged = 1 WHERE alertId = :alertId")
    suspend fun acknowledgeAlert(alertId: String)

    @Query("DELETE FROM alert_feed")
    suspend fun clearAll()
}
