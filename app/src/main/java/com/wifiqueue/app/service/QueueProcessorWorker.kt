package com.wifiqueue.app.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wifiqueue.app.service.WifiConnectionMonitor
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodic safety net: reconciles the queue with the real DownloadManager
 * state and starts the next queued download when Wi-Fi is available. Runs at
 * least every 15 minutes even when the app is closed.
 *
 * Every engine operation it performs goes through QueueSequencer's global
 * mutex, and the one-time variant (enqueueNow) uses unique KEEP work, so
 * overlapping invocations cannot create duplicate active downloads.
 */
class QueueProcessorWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val connectivityMonitor = WifiConnectionMonitor(applicationContext)
            val queueSequencer = QueueSequencer(applicationContext)

            val wifiConnected = connectivityMonitor.observeWifiConnection().first()
            queueSequencer.processQueue(wifiConnected)

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "wifi_queue_processor"
        private const val IMMEDIATE_WORK_NAME = "wifi_queue_processor_now"

        /**
         * Runs one reconciliation pass as soon as possible. Unique work with
         * KEEP prevents a pile-up of duplicate workers when several events
         * (Wi-Fi change, user action, broadcast) trigger at the same time.
         */
        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<QueueProcessorWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

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
