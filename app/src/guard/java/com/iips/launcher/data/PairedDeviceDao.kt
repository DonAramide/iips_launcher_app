package com.iips.launcher.data

import androidx.room.*

@Dao
interface PairedDeviceDao {
    @Query("SELECT * FROM paired_devices")
    fun getAllDevicesFlow(): kotlinx.coroutines.flow.Flow<List<PairedDeviceEntity>>

    @Query("SELECT * FROM paired_devices")
    suspend fun getAllDevices(): List<PairedDeviceEntity>

    @Query("SELECT * FROM paired_devices WHERE deviceId = :deviceId")
    fun getDeviceByIdFlow(deviceId: String): kotlinx.coroutines.flow.Flow<PairedDeviceEntity?>

    @Query("SELECT * FROM paired_devices WHERE deviceId = :deviceId")
    suspend fun getDeviceById(deviceId: String): PairedDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: PairedDeviceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevices(devices: List<PairedDeviceEntity>)

    @Query("DELETE FROM paired_devices")
    suspend fun clearAll()
}
