package com.iips.launcher.deployment.data

import androidx.room.*

/**
 * Tracks the history and current state of an enterprise rollout.
 */
@Entity(tableName = "deployment_records")
data class DeploymentRecord(
    @PrimaryKey val deploymentId: String,
    val version: String,
    val apkUrl: String,
    val cohortId: String?,
    val state: String, // PENDING, INSTALLING, VALIDATING, CONVERGED, FAILED, ROLLED_BACK
    val startTime: Long,
    val endTime: Long? = null,
    val errorMessage: String? = null,
    val rollbackReason: String? = null
)

/**
 * Saves a snapshot of critical system state before a deployment.
 */
@Entity(tableName = "deployment_checkpoints")
data class DeploymentCheckpoint(
    @PrimaryKey val deploymentId: String,
    val timestamp: Long,
    val previousVersionName: String,
    val previousVersionCode: Int,
    val activePoliciesHash: String,
    val kioskEnabled: Boolean
)

@Dao
interface DeploymentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeployment(record: DeploymentRecord)

    @Update
    suspend fun updateDeployment(record: DeploymentRecord)

    @Query("SELECT * FROM deployment_records WHERE deploymentId = :id")
    suspend fun getDeploymentById(id: String): DeploymentRecord?

    @Query("SELECT * FROM deployment_records ORDER BY startTime DESC LIMIT 50")
    suspend fun getAllDeployments(): List<DeploymentRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckpoint(checkpoint: DeploymentCheckpoint)

    @Query("SELECT * FROM deployment_checkpoints WHERE deploymentId = :id")
    suspend fun getCheckpointForDeployment(id: String): DeploymentCheckpoint?

    @Query("DELETE FROM deployment_checkpoints WHERE deploymentId = :id")
    suspend fun deleteCheckpoint(id: String)
}
