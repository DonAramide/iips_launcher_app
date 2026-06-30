package com.iips.launcher.auth

import android.content.Context
import com.iips.launcher.data.PairedDeviceDao
import com.iips.launcher.data.PairedDeviceEntity
import com.iips.launcher.network.GuardPairingService
import com.iips.launcher.network.models.GuardPairingRequest
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GuardPairingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pairingService: GuardPairingService,
    private val deviceDao: PairedDeviceDao
) {

    suspend fun pairDevice(pairingToken: String, pairingMethod: String): Result<PairedDeviceEntity> = withContext(Dispatchers.IO) {
        try {
            val token = SecurePreferences.getGuardAuthToken(context)
                ?: return@withContext Result.failure(Exception("No authorization token"))

            val request = GuardPairingRequest(pairingToken, pairingMethod)
            val response = pairingService.submitPairingToken("Bearer $token", request)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val entity = PairedDeviceEntity(
                    deviceId = body.deviceId,
                    deviceName = body.deviceName,
                    connectivityStatus = body.connectivityStatus,
                    securityStatus = body.securityStatus,
                    lastSeen = System.currentTimeMillis(),
                    pairedAt = body.pairedAt,
                    branchId = body.branchId,
                    branchName = body.branchName,
                    merchantName = body.merchantName,
                    managerRole = body.managerRole,
                    lastSyncAt = body.lastSyncAt,
                    batteryLevel = body.batteryLevel,
                    networkStatus = body.networkStatus,
                    guardStatus = body.guardStatus,
                    deviceHealthStatus = body.deviceHealthStatus,
                    unreadAlertCount = body.unreadAlertCount,
                    lat = body.lat,
                    lng = body.lng,
                    isSimPresent = body.isSimPresent,
                    simOperator = body.simOperator,
                    simNetworkType = body.simNetworkType,
                    uptime = body.uptime,
                    locationName = body.locationName
                )
                deviceDao.insertDevice(entity)
                Result.success(entity)
            } else {
                Result.failure(Exception("Pairing failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateDeviceStatus(deviceId: String, status: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = SecurePreferences.getGuardAuthToken(context)
                ?: return@withContext Result.failure(Exception("No authorization token"))

            val request = com.iips.launcher.network.models.PairingStatusRequest(status)
            val response = pairingService.updatePairingStatus("Bearer $token", deviceId, request)

            if (response.isSuccessful && response.body() != null) {
                val dbDevice = deviceDao.getDeviceById(deviceId)
                if (dbDevice != null) {
                    val updated = dbDevice.copy(
                        securityStatus = response.body()!!.status,
                        lastSeen = System.currentTimeMillis()
                    )
                    deviceDao.insertDevice(updated)
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to update status: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
