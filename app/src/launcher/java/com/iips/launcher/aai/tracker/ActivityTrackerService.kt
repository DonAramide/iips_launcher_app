package com.iips.launcher.aai.tracker

import android.app.Service
import android.content.Intent
import android.os.IBinder

class ActivityTrackerService : Service() {

    private lateinit var sessionCoordinator: ActivitySessionCoordinator

    override fun onCreate() {
        super.onCreate()
        sessionCoordinator = ActivitySessionCoordinator(applicationContext)
        sessionCoordinator.startCoordinating()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Run as foreground service (logic omitted for brevity)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionCoordinator.stopCoordinating()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
