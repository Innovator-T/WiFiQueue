package com.wifiqueue.app.service

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.wifiqueue.app.data.AppDatabase
import com.wifiqueue.app.data.DownloadItem
import com.wifiqueue.app.data.DownloadStatus
import kotlinx.coroutines.flow.first

class QueueManager(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val storageManager = StorageManager(context)
    private val notificationHelper = DownloadNotificationHelper(context)
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    suspend fun startQueueProcessing() {
        val queuedItems = database.downloadDao().getByStatus(DownloadStatus.WAITING).first() +
            database.downloadDao().getByStatus(DownloadStatus.QUEUED).first() +
            database.downloadDao().getByStatus(DownloadStatus.PAUSED).first()

        queuedItems.distinctBy { it.id }.forEach { item ->
            if (item.status == DownloadStatus.PAUSED) {
                resumeDownload(item)
            } else {
                startDownload(item)
            }
        }
    }

    suspend fun startDownload(item: DownloadItem) {
        val dao = database.downloadDao()

        if (!storageManager.hasEnoughSpace(50L * 1_024L * 1_024L)) {
            dao.update(item.copy(status = DownloadStatus.FAILED, updatedAt = System.currentTimeMillis()))
            notificationHelper.showDownloadFailed(item.id, item.title)
            return
        }

        try {
            val file = storageManager.createDownloadFile(item.fileName)
            val request = DownloadManager.Request(Uri.parse(item.url))
                .setTitle(item.title)
                .setDescription(item.url)
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(file))

            val id = downloadManager.enqueue(request)
            dao.update(
                item.copy(
                    status = DownloadStatus.DOWNLOADING,
                    storagePath = file.absolutePath,
                    updatedAt = System.currentTimeMillis()
                )
            )

            val query = DownloadManager.Query().setFilterById(id)
            val cursor = downloadManager.query(query)
            if (cursor != null && cursor.moveToFirst()) {
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val progress = if (total > 0L) ((downloaded * 100) / total).toInt() else 0
                dao.update(item.copy(progress = progress, fileSizeBytes = total, updatedAt = System.currentTimeMillis()))
                cursor.close()
            }
        } catch (e: Exception) {
            Log.e("QueueManager", "Failed to enqueue download", e)
            dao.update(item.copy(status = DownloadStatus.FAILED, updatedAt = System.currentTimeMillis()))
            notificationHelper.showDownloadFailed(item.id, item.title)
        }
    }

    suspend fun pauseDownload(item: DownloadItem) {
        database.downloadDao().update(
            item.copy(
                status = DownloadStatus.PAUSED,
                updatedAt = System.currentTimeMillis()
            )
        )
        notificationHelper.cancel(item.id)
    }

    suspend fun resumeDownload(item: DownloadItem) {
        val dao = database.downloadDao()
        dao.update(
            item.copy(
                status = DownloadStatus.QUEUED,
                updatedAt = System.currentTimeMillis()
            )
        )
        startDownload(item.copy(status = DownloadStatus.QUEUED))
    }

    suspend fun cancelDownload(item: DownloadItem) {
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
}
