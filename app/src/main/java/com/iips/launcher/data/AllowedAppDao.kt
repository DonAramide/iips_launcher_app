package com.iips.launcher.data

import androidx.room.*

@Dao
interface AllowedAppDao {
    @Query("SELECT * FROM allowed_apps ORDER BY appName ASC")
    suspend fun getAll(): List<AllowedApp>

    @Query("SELECT * FROM allowed_apps WHERE packageName = :packageName")
    suspend fun getByPackageName(packageName: String): AllowedApp?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: AllowedApp)

    @Delete
    suspend fun delete(app: AllowedApp)

    @Query("DELETE FROM allowed_apps WHERE packageName = :packageName")
    suspend fun deleteByPackageName(packageName: String)

    @Query("DELETE FROM allowed_apps")
    suspend fun deleteAll()
}





