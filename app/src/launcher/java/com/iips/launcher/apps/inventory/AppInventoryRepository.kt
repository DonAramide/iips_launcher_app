package com.iips.launcher.apps.inventory

import com.iips.launcher.apps.inventory.data.AppInventoryDao
import com.iips.launcher.apps.inventory.data.AppInventoryEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppInventoryRepository @Inject constructor(
    private val appInventoryDao: AppInventoryDao
) {
    suspend fun getFullInventory(): List<AppInventoryEntity> {
        return appInventoryDao.getAll()
    }

    suspend fun updateInventory(apps: List<AppInventoryEntity>) {
        appInventoryDao.insertAll(apps)
    }

    suspend fun removeApp(packageName: String) {
        appInventoryDao.delete(packageName)
    }
}
