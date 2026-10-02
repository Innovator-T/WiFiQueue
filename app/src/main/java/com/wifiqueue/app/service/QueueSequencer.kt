package com.wifiqueue.app.service

import android.app.DownloadManager
import android.content.Context
import android.util.Log
import com.wifiqueue.app.data.AppDatabase
import com.wifiqueue.app.data.DownloadStatus
import kotlinx.coroutines.flow.first

class QueueSequencer(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val queueManager = QueueManager(context)
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val notificationHelper = DownloadNotificationHelper(context)

    suspend fun processQueue(wifiConnected: Boolean) {
        val dao = database.downloadDao()

        // Only process if Wi-Fi is connected
        if (!wifiConnected) {
            Log.d(TAG, "Wi-Fi not connected, pausing queue processing")
            pauseActiveDownloads()
            return
        }

        // Get current queue state
        val waiting = dao.getByStatus(DownloadStatus.WAITING).first()
        val queued = dao.getByStatus(DownloadStatus.QUEUED).first()
        val downloading = dao.getByStatus(DownloadStatus.DOWNLOADING).first()
        val paused = dao.getByStatus(DownloadStatus.PAUSED).first()

        Log.d(TAG, "Queue state: waiting=${waiting.size}, queued=${queued.size}, downloading=${downloading.size}, paused=${paused.size}")

        // Resume any paused downloads
        paused.forEach { item ->
            Log.d(TAG, "Resuming paused download: ${item.title}")
            resumeDownload(item)
        }

        // Check active downloads and update their progress
        downloading.forEach { item ->
            updateDownloadProgress(item)
        }

        // Start next download if there's capacity
        if (downloading.isEmpty()) {
            val nextItem = (waiting + queued).firstOrNull()
            if (nextItem != null) {
                Log.d(TAG, "Starting next download: ${nextItem.title}")
                queueManager.startDownload(nextItem)
            }
        }
    }

    private suspend fun updateDownloadProgress(item: com.wifiqueue.app.data.DownloadItem) {
        try {
            val query = DownloadManager.Query().setFilterById(item.id)
            val cursor = downloadManager.query(query)

            if (cursor != null && cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                val progress = if (totalBytes > 0L) ((bytesDownloaded * 100) / totalBytes).toInt() else 0

                database.downloadDao().update(
                    item.copy(
                        progress = progress,
                        fileSizeBytes = totalBytes,
                        updatedAt = System.currentTimeMillis()
                    )
                )

                notificationHelper.showDownloadProgress(item.id, item.title, progress, totalBytes)

                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        Log.d(TAG, "Download completed: ${item.title}")
                        database.downloadDao().update(
                            item.copy(
                                status = DownloadStatus.COMPLETED,
                                progress = 100,
                                completedAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        notificationHelper.showDownloadComplete(item.id, item.title)
                    }

                    DownloadManager.STATUS_FAILED -> {
                        Log.e(TAG, "Download failed: ${item.title}")
                        database.downloadDao().update(
                            item.copy(
                                status = DownloadStatus.FAILED,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        notificationHelper.showDownloadFailed(item.id, item.title)
                    }
                }

                cursor.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating download progress for ${item.title}", e)
        }
    }

    suspend fun resumeDownload(item: com.wifiqueue.app.data.DownloadItem) {
        val dao = database.downloadDao()
        dao.update(
            item.copy(
                status = DownloadStatus.QUEUED,
                updatedAt = System.currentTimeMillis()
            )
        )
        queueManager.startDownload(item.copy(status = DownloadStatus.QUEUED))
    }

    suspend fun cancelDownload(item: com.wifiqueue.app.data.DownloadItem) {
        val dao = database.downloadDao()
        dao.update(
            item.copy(
                status = DownloadStatus.CANCELLED,
                updatedAt = System.currentTimeMillis()
            )
        )
        notificationHelper.cancel(item.id)
        item.storagePath?.let { path ->
            val file = java.io.File(path)
            if (file.exists()) file.delete()
        }
    }

    private suspend fun pauseActiveDownloads() {
        val dao = database.downloadDao()
        val downloading = dao.getByStatus(DownloadStatus.DOWNLOADING).first()

        downloading.forEach { item ->
            Log.d(TAG, "Pausing download due to Wi-Fi loss: ${item.title}")
            dao.update(
                item.copy(
                    status = DownloadStatus.PAUSED,
                    updatedAt = System.currentTimeMillis()
                )
            )
            notificationHelper.cancel(item.id)
        }
    }

    companion object {
        private const val TAG = "QueueSequencer"
    }
}
