package com.wifiqueue.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wifiqueue.app.data.AppDatabase
import com.wifiqueue.app.data.DownloadStatus
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val dao = AppDatabase.getDatabase(applicationContext).downloadDao()
        val queuedItems = dao.getByStatus(DownloadStatus.QUEUED).first() +
            dao.getByStatus(DownloadStatus.WAITING).first() +
            dao.getByStatus(DownloadStatus.PAUSED).first()

        val queueManager = QueueManager(applicationContext)
        queuedItems.distinctBy { it.id }.forEach { item ->
            when (item.status) {
                DownloadStatus.PAUSED -> queueManager.resumeDownload(item)
                else -> queueManager.startDownload(item)
            }
        }

        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "wifi_queue_background_downloads"

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<DownloadWorker>(15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }
    }
}
