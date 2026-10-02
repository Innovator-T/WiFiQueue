package com.wifiqueue.app

import android.app.Application

class WifiQueueApp : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
}
