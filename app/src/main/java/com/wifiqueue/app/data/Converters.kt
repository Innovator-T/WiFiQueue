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
    val status: DownloadStatus = DownloadStatus.WAITING,
    val progress: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val isWifiOnly: Boolean = true,
    val storagePath: String? = null,
    val completedAt: Long? = null
)
