package com.iips.launcher.guard.data

import androidx.room.*

@Dao
interface GeofenceRuleDao {
    @Query("SELECT * FROM geofence_rules")
    suspend fun getAllRules(): List<GeofenceRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRules(rules: List<GeofenceRuleEntity>)

    @Query("DELETE FROM geofence_rules WHERE id = :id")
    suspend fun deleteRuleById(id: String)

    @Query("DELETE FROM geofence_rules")
    suspend fun clearAllRules()
}
