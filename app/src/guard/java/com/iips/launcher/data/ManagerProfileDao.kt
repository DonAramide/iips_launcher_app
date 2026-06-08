package com.iips.launcher.data

import androidx.room.*

@Dao
interface ManagerProfileDao {
    @Query("SELECT * FROM manager_profile LIMIT 1")
    suspend fun getProfile(): ManagerProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: ManagerProfileEntity)

    @Query("DELETE FROM manager_profile")
    suspend fun clearProfile()
}
