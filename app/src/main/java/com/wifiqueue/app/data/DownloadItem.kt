package com.wifiqueue.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_items")
data class DownloadItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val url: String,
    val fileName: String = title,
    val fileSizeBytes: Long = 0L,
    /**
     * Bytes actually transferred, as reported by DownloadManager
     * (COLUMN_BYTES_DOWNLOADED_SO_FAR). Never derived from a percentage.
     */
    val downloadedBytes: Long = 0L,
    /**
     * Human-readable snapshot of the last state Android DownloadManager
     * reported for this job (pending / paused / running + its reason code).
     * Shown in the queue so a job that never starts is visible instead of
     * looking like an ordinary 0% download.
     */
    val systemState: String? = null,
    val status: DownloadStatus = DownloadStatus.WAITING,
    val progress: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val isWifiOnly: Boolean = true,
    val storagePath: String? = null,
    val completedAt: Long? = null,
    val downloadManagerId: Long? = null,
    val errorMessage: String? = null
)
