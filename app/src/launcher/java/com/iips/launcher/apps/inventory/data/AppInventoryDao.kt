package com.iips.launcher.apps.inventory.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AppInventoryDao {
    @Query("SELECT * FROM app_inventory")
    suspend fun getAll(): List<AppInventoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<AppInventoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: AppEventEntity)

    @Query("DELETE FROM app_inventory WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
