package com.wifiqueue.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wifiqueue.app.data.AppDatabase
import com.wifiqueue.app.data.AppSettings
import com.wifiqueue.app.data.DownloadItem
import com.wifiqueue.app.data.DownloadStatus
import com.wifiqueue.app.data.SettingsRepository
import com.wifiqueue.app.data.StorageInfo
import com.wifiqueue.app.service.QueueSequencer
import com.wifiqueue.app.service.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val dao = database.downloadDao()
    private val queueSequencer = QueueSequencer(application)
    private val settingsRepository = SettingsRepository(application)
    private val storageManager = StorageManager(application)

    private val _wifiConnected = MutableStateFlow(false)
    val wifiConnected: StateFlow<Boolean> = _wifiConnected.asStateFlow()

    /** Persisted preferences; the engine reads the same store, not a copy. */
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _storageInfo = MutableStateFlow<StorageInfo?>(null)
    val storageInfo: StateFlow<StorageInfo?> = _storageInfo.asStateFlow()

    val downloads: Flow<List<DownloadItem>> = dao.getAll()

    init {
        // Progress loop: reconciles the active download with the system engine
        // and starts the next queued item when it completes. Whether losing
        // Wi-Fi should stop anything is decided by the sequencer from the
        // persisted settings, so this tick is unconditional. The loop dies with
        // the ViewModel, so it cannot run uncontrolled in the background.
        viewModelScope.launch {
            while (true) {
                delay(PROGRESS_REFRESH_MS)
                runCatching { queueSequencer.processQueue(_wifiConnected.value) }
            }
        }
    }

    fun updateWifiState(connected: Boolean) {
        _wifiConnected.value = connected
        viewModelScope.launch {
            runCatching { queueSequencer.processQueue(connected) }
        }
    }

    /** Wi-Fi-only toggle: applied to new requests and to network-loss handling. */
    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setWifiOnly(enabled)
            runCatching { queueSequencer.processQueue(_wifiConnected.value) }
        }
    }

    /** Auto-resume toggle: whether network loss requeues or pauses the download. */
    fun setAutoResume(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoResume(enabled)
            runCatching { queueSequencer.processQueue(_wifiConnected.value) }
        }
    }

    /** Overnight toggle: starting a queued download obeys the night window. */
    fun setDownloadOvernight(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDownloadOvernight(enabled)
            runCatching { queueSequencer.processQueue(_wifiConnected.value) }
        }
    }

    /** Re-reads device capacity for the Settings tab. */
    fun refreshStorageInfo() {
        viewModelScope.launch {
            _storageInfo.value = withContext(Dispatchers.IO) {
                runCatching { readStorageInfo() }.getOrNull()
            }
        }
    }

    fun addDownload(title: String, url: String) {
        val sanitizedFileName = title.replace(Regex("[<>:\"/\\\\|?*]"), "_")
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
            // Adds become active only once the user queues them; no work needed.
        }
    }

    /**
     * Queue screen's main action button. Every path runs through the sequencer
     * so transitions, DownloadManager jobs and database state stay consistent.
     */
    fun toggleDownload(item: DownloadItem) {
        viewModelScope.launch {
            when (item.status) {
                DownloadStatus.WAITING -> {
                    dao.update(item.copy(status = DownloadStatus.QUEUED, updatedAt = System.currentTimeMillis()))
                }
                DownloadStatus.QUEUED -> {
                    dao.update(item.copy(status = DownloadStatus.WAITING, updatedAt = System.currentTimeMillis()))
                }
                // REAL pause: removes the live DownloadManager request and
                // partial data before recording the PAUSED state.
                DownloadStatus.DOWNLOADING -> {
                    runCatching { queueSequencer.pauseDownload(item) }
                }
                // REAL resume: clears the old job and re-queues; the follow-up
                // processQueue call performs the actual sequential start.
                DownloadStatus.PAUSED -> {
                    runCatching { queueSequencer.resumeDownload(item) }
                }
                // Retry of failed or previously cancelled downloads.
                DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                    dao.update(
                        item.copy(
                            status = DownloadStatus.QUEUED,
                            progress = 0,
                            errorMessage = null,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                DownloadStatus.COMPLETED -> {
                    // Terminal state; the button is disabled for COMPLETED.
                    return@launch
                }
            }
            // Let the sequencer decide what runs next (sequential queue).
            runCatching { queueSequencer.processQueue(_wifiConnected.value) }
        }
    }

    /** Delete a record: cancel any live job first, then remove the row. */
    fun deleteDownload(item: DownloadItem) {
        viewModelScope.launch {
            runCatching { queueSequencer.cancelDownload(item) }
            dao.delete(item)
            runCatching { queueSequencer.processQueue(_wifiConnected.value) }
        }
    }

    /** Real capacity of the download volume plus what the queue still needs. */
    private suspend fun readStorageInfo(): StorageInfo {
        val unfinished = downloads.first().filter { it.status != DownloadStatus.COMPLETED }
        return StorageInfo(
            usedBytes = storageManager.getUsedStorageBytes(),
            availableBytes = storageManager.getAvailableStorageBytes(),
            totalBytes = storageManager.getTotalStorageBytes(),
            queueRequiredBytes = unfinished.sumOf { it.fileSizeBytes },
            queueItemsWithUnknownSize = unfinished.count { it.fileSizeBytes <= 0L }
        )
    }

    companion object {
        /** Poll cadence while the app UI is alive; bounded by the ViewModel scope. */
        const val PROGRESS_REFRESH_MS = 2_500L
    }
}
