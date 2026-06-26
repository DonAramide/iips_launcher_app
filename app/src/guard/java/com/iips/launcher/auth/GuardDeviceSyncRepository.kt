package com.iips.launcher.auth

import android.content.Context
import com.iips.launcher.data.PairedDeviceDao
import com.iips.launcher.data.PairedDeviceEntity
import com.iips.launcher.network.GuardPairingService
import com.iips.launcher.network.models.GuardDeviceSummaryResponse
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GuardDeviceSyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pairingService: GuardPairingService,
    private val deviceDao: PairedDeviceDao
) {

    suspend fun syncDevices(): Result<List<PairedDeviceEntity>> = withContext(Dispatchers.IO) {
        try {
            val token = SecurePreferences.getGuardAuthToken(context)
                ?: return@withContext Result.failure(Exception("No authorization token"))

            val response = pairingService.getPairedDevices("Bearer $token")
            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!
                val entities = list.map { body ->
                    PairedDeviceEntity(
                        deviceId = body.deviceId,
                        deviceName = body.deviceName,
                        connectivityStatus = body.connectivityStatus,
                        securityStatus = body.securityStatus,
                        lastSeen = body.lastSeen,
                        pairedAt = body.pairedAt,
                        branchId = body.branchId,
                        branchName = body.branchName,
                        merchantName = body.merchantName,
                        managerRole = body.managerRole,
                        lastSyncAt = System.currentTimeMillis(),
                        batteryLevel = body.batteryLevel,
                        networkStatus = body.networkStatus,
                        guardStatus = body.guardStatus,
                        deviceHealthStatus = body.deviceHealthStatus,
                        unreadAlertCount = body.unreadAlertCount
                    )
                }
                
                deviceDao.clearAll()
                deviceDao.insertDevices(entities)
                
                Result.success(entities)
            } else {
                Result.failure(Exception("Failed to fetch devices: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncDeviceDetails(deviceId: String): Result<PairedDeviceEntity> = withContext(Dispatchers.IO) {
        try {
            val token = SecurePreferences.getGuardAuthToken(context)
                ?: return@withContext Result.failure(Exception("No authorization token"))

            val response = pairingService.getDeviceDetails("Bearer $token", deviceId)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val entity = PairedDeviceEntity(
                    deviceId = body.deviceId,
                    deviceName = body.deviceName,
                    connectivityStatus = body.connectivityStatus,
                    securityStatus = body.securityStatus,
                    lastSeen = body.lastSeen,
                    pairedAt = body.pairedAt,
                    branchId = body.branchId,
                    branchName = body.branchName,
                    merchantName = body.merchantName,
                    managerRole = body.managerRole,
                    lastSyncAt = System.currentTimeMillis(),
                    batteryLevel = body.batteryLevel,
                    networkStatus = body.networkStatus,
                    guardStatus = body.guardStatus,
                    deviceHealthStatus = body.deviceHealthStatus,
                    unreadAlertCount = body.unreadAlertCount
                )
                deviceDao.insertDevice(entity)
                Result.success(entity)
            } else {
                Result.failure(Exception("Failed to fetch device details: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDeviceSummary(): Result<GuardDeviceSummaryResponse> = withContext(Dispatchers.IO) {
        try {
            val token = SecurePreferences.getGuardAuthToken(context)
                ?: return@withContext Result.failure(Exception("No authorization token"))

            val response = pairingService.getDeviceSummary("Bearer $token")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch device summary: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateDeviceStatus(deviceId: String, status: String): Result<com.iips.launcher.network.models.PairingStatusResponse> = withContext(Dispatchers.IO) {
        try {
            val token = SecurePreferences.getGuardAuthToken(context)
                ?: return@withContext Result.failure(Exception("No authorization token"))

            val request = com.iips.launcher.network.models.PairingStatusRequest(status = status)
            val response = pairingService.updatePairingStatus("Bearer $token", deviceId, request)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to update status: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
