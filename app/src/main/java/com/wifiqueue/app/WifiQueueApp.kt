package com.wifiqueue.app

import android.app.Application
import com.wifiqueue.app.data.AppDatabase
import com.wifiqueue.app.service.DownloadWorker

class WifiQueueApp : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        DownloadWorker.enqueuePeriodic(this)
    }
}
