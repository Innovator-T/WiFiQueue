package com.wifiqueue.app

import android.app.Application
import com.wifiqueue.app.data.AppDatabase
import com.wifiqueue.app.service.QueueProcessorWorker

class WifiQueueApp : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        // Enqueue periodic queue processing work
        QueueProcessorWorker.enqueue(this)
    }
}
