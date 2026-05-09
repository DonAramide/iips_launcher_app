package com.iips.launcher.apps.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.iips.launcher.apps.inventory.AppInventoryRepository
import com.iips.launcher.apps.inventory.data.AppEventEntity
import com.iips.launcher.apps.inventory.data.AppInventoryDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PackageMonitorReceiver : BroadcastReceiver() {
    private val TAG = "PackageMonitor"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Inject
    lateinit var appInventoryDao: AppInventoryDao

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val packageName = intent.data?.schemeSpecificPart ?: return
        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

        Log.d(TAG, "Package Event: \$action for \$packageName (Replacing: \$isReplacing)")

        scope.launch {
            when (action) {
                Intent.ACTION_PACKAGE_ADDED -> {
                    if (!isReplacing) {
                        appInventoryDao.insertEvent(AppEventEntity(packageName = packageName, eventType = "INSTALLED"))
                    }
                }
                Intent.ACTION_PACKAGE_REMOVED -> {
                    if (!isReplacing) {
                        appInventoryDao.insertEvent(AppEventEntity(packageName = packageName, eventType = "REMOVED"))
                        appInventoryDao.delete(packageName)
                    }
                }
                Intent.ACTION_PACKAGE_REPLACED -> {
                    appInventoryDao.insertEvent(AppEventEntity(packageName = packageName, eventType = "UPDATED"))
                }
            }
        }
    }
}
