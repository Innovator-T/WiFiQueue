package com.wifiqueue.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wifiqueue.app.data.AppDatabase
import com.wifiqueue.app.data.DownloadItem
import com.wifiqueue.app.data.DownloadStatus
import com.wifiqueue.app.service.QueueSequencer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DownloadViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val dao = database.downloadDao()
    private val queueSequencer = QueueSequencer(application)

    private val _wifiConnected = MutableStateFlow(false)
    val wifiConnected: StateFlow<Boolean> = _wifiConnected.asStateFlow()

    val downloads: Flow<List<DownloadItem>> = dao.getAll()

    fun updateWifiState(connected: Boolean) {
        _wifiConnected.value = connected
        viewModelScope.launch {
            queueSequencer.processQueue(connected)
        }
    }

    fun addDownload(title: String, url: String) {
        val sanitizedFileName = title.replace(Regex("[<>:\"/\\|?*]"), "_")
        val item = DownloadItem(
            title = title,
            url = url,
            fileName = sanitizedFileName,
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
            when (item.status) {
                DownloadStatus.WAITING -> {
                    dao.update(item.copy(status = DownloadStatus.QUEUED, updatedAt = System.currentTimeMillis()))
                }
                DownloadStatus.QUEUED -> {
                    dao.update(item.copy(status = DownloadStatus.WAITING, updatedAt = System.currentTimeMillis()))
                }
                DownloadStatus.PAUSED -> {
                    queueSequencer.resumeDownload(item)
                }
                DownloadStatus.FAILED -> {
                    dao.update(item.copy(status = DownloadStatus.QUEUED, progress = 0, updatedAt = System.currentTimeMillis()))
                }
                else -> {}
            }
        }
    }

    fun deleteDownload(item: DownloadItem) {
        viewModelScope.launch {
            queueSequencer.cancelDownload(item)
            dao.delete(item)
        }
    }
}
