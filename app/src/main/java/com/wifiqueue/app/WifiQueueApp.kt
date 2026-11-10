package com.wifiqueue.app

import android.app.Application
import com.wifiqueue.app.data.AppDatabase
import com.wifiqueue.app.service.QueueProcessorWorker

class WifiQueueApp : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        // Periodic safety net plus one immediate pass so downloads that were
        // DOWNLOADING last time the process died are reconciled right away.
        QueueProcessorWorker.enqueue(this)
        QueueProcessorWorker.enqueueNow(this)
    }
}
