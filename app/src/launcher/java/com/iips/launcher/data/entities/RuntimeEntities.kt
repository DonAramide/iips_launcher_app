package com.iips.launcher.data.entities

import androidx.room.*

/**
 * Entity for persisting device presence and health snapshots.
 */
@Entity(tableName = "runtime_state_snapshots")
data class RuntimeStateSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val state: String,
    val isWorkManagerHealthy: Boolean,
    val isDatabaseHealthy: Boolean,
    val isTelemetryHealthy: Boolean,
    val driftCount: Int
)

/**
 * Entity for logging policy drift events.
 */
@Entity(tableName = "policy_drift_events")
data class PolicyDriftEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val driftType: String,
    val details: String
)

/**
 * Entity for tracking autonomous recovery attempts.
 */
@Entity(tableName = "runtime_recovery_events")
data class RuntimeRecoveryEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val eventType: String,
    val actionTaken: String,
    val success: Boolean,
    val errorMessage: String? = null
)

@Dao
interface RuntimeDao {
    @Insert
    suspend fun insertSnapshot(snapshot: RuntimeStateSnapshot)

    @Insert
    suspend fun insertDriftEvent(event: PolicyDriftEvent)

    @Insert
    suspend fun insertRecoveryEvent(event: RuntimeRecoveryEvent)

    @Query("SELECT * FROM runtime_state_snapshots ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecentSnapshots(): List<RuntimeStateSnapshot>

    @Query("SELECT * FROM policy_drift_events ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecentDriftEvents(): List<PolicyDriftEvent>
}
