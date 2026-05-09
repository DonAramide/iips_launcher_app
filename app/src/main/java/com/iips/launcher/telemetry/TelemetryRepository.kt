package com.iips.launcher.telemetry

import com.google.gson.Gson
import com.iips.launcher.network.ConfigService
import com.iips.launcher.network.models.HeartbeatRequest
import com.iips.launcher.telemetry.data.TelemetryDao
import com.iips.launcher.telemetry.data.TelemetryEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelemetryRepository @Inject constructor(
    private val telemetryDao: TelemetryDao,
    private val configService: ConfigService
) {
    private val gson = Gson()

    suspend fun saveToOfflineQueue(heartbeat: HeartbeatRequest) {
        val entity = TelemetryEntity(
            payload = gson.toJson(heartbeat)
        )
        telemetryDao.insert(entity)
    }

    suspend fun getPendingTelemetry(): List<TelemetryEntity> {
        return telemetryDao.getPendingBatch()
    }

    suspend fun deleteBatch(ids: List<Long>) {
        telemetryDao.deleteBatch(ids)
    }

    suspend fun sendHeartbeat(
        token: String,
        signature: String,
        timestamp: String,
        nonce: String,
        request: HeartbeatRequest
    ) = configService.sendHeartbeat("Bearer $token", signature, timestamp, nonce, request)

    suspend fun sendHeartbeatBatch(
        token: String,
        signature: String,
        timestamp: String,
        nonce: String,
        requests: List<HeartbeatRequest>
    ) = configService.sendHeartbeatBatch("Bearer $token", signature, timestamp, nonce, requests)
}
