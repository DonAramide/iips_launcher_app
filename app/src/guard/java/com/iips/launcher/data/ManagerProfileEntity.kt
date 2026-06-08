package com.iips.launcher.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "manager_profile")
data class ManagerProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val email: String,
    val phone: String,
    val role: String
)
