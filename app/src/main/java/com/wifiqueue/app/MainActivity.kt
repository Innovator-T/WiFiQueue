package com.wifiqueue.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wifiqueue.app.data.AppDatabase
import com.wifiqueue.app.data.DownloadItem
import com.wifiqueue.app.data.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DownloadViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).downloadDao()

    private val _wifiConnected = MutableStateFlow(false)
    val wifiConnected: StateFlow<Boolean> = _wifiConnected.asStateFlow()

    val downloads: Flow<List<DownloadItem>> = dao.getAll()

    fun updateWifiState(connected: Boolean) {
        _wifiConnected.value = connected
        if (connected) {
            processQueuedDownloads()
        }
    }

    fun addDownload(title: String, url: String) {
        val item = DownloadItem(
            title = title,
            url = url,
            fileName = title,
            fileSizeBytes = 0L,
            status = DownloadStatus.WAITING,
            isWifiOnly = true
        )

        viewModelScope.launch {
            dao.insert(item)
        }
    }

    fun toggleDownload(item: DownloadItem) {
        viewModelScope.launch {
            val updated = when (item.status) {
                DownloadStatus.WAITING -> item.copy(status = DownloadStatus.QUEUED, updatedAt = System.currentTimeMillis())
                DownloadStatus.QUEUED -> item.copy(status = DownloadStatus.WAITING, updatedAt = System.currentTimeMillis())
                DownloadStatus.PAUSED -> item.copy(status = DownloadStatus.QUEUED, updatedAt = System.currentTimeMillis())
                else -> item
            }
            dao.update(updated)
        }
    }

    fun deleteDownload(item: DownloadItem) {
        viewModelScope.launch {
            dao.delete(item)
        }
    }

    fun setDownloadProgress(itemId: Long, progress: Int) {
        viewModelScope.launch {
            val current = dao.getAll().first().firstOrNull { it.id == itemId } ?: return@launch
            dao.update(
                current.copy(
                    progress = progress.coerceIn(0, 100),
                    status = if (progress >= 100) DownloadStatus.COMPLETED else DownloadStatus.DOWNLOADING,
                    updatedAt = System.currentTimeMillis(),
                    completedAt = if (progress >= 100) System.currentTimeMillis() else null
                )
            )
        }
    }

    private fun processQueuedDownloads() {
        viewModelScope.launch {
            val queuedItems = dao.getAll().first().filter {
                it.status == DownloadStatus.WAITING || it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.PAUSED
            }

            queuedItems.forEach { item ->
                dao.update(
                    item.copy(
                        status = DownloadStatus.QUEUED,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }
}
