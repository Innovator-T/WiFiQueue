package com.wifiqueue.app.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wifiqueue.app.data.AppDatabase
import com.wifiqueue.app.util.ConnectivityMonitor
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class QueueProcessorWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val database = AppDatabase.getDatabase(applicationContext)
            val connectivityMonitor = ConnectivityMonitor(applicationContext)
            val queueSequencer = QueueSequencer(applicationContext)

            // Check Wi-Fi state
            val wifiConnected = connectivityMonitor.observeWifiState().first()

            // Process queue based on Wi-Fi connectivity
            queueSequencer.processQueue(wifiConnected)

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "wifi_queue_processor"

        fun enqueue(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<QueueProcessorWorker>(
                15, TimeUnit.MINUTES
            )
                .setBackoffPolicy(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }
}
