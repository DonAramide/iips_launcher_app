package com.iips.launcher.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface AppPolicyDao {
    @Query("SELECT * FROM app_policies")
    fun getAllPolicies(): List<AppPolicy>

    @Query("SELECT * FROM app_policies WHERE packageName = :packageName")
    fun getPolicy(packageName: String): AppPolicy?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(policies: List<AppPolicy>)

    @Query("DELETE FROM app_policies")
    fun deleteAll()

    @Transaction
    fun replaceAll(policies: List<AppPolicy>) {
        deleteAll()
        insertAll(policies)
    }
}
