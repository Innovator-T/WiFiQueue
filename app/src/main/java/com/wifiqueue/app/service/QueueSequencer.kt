package com.wifiqueue.app.service

import android.content.Context
import android.util.Log
import com.wifiqueue.app.data.AppDatabase
import com.wifiqueue.app.data.DownloadStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes every queue/engine operation behind a process-wide mutex, so the
 * periodic worker, the UI actions and the completion receiver can never start
 * two DownloadManager requests at the same time.
 *
 * Sequential rule enforced here: there is at most one DOWNLOADING item at any
 * moment; the next item starts only when no active item remains after
 * reconciliation. CANCELLED items are skipped, FAILED items stay failed until
 * the user retries, and user-paused items are never re-enqueued automatically.
 */
class QueueSequencer(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val dao = database.downloadDao()
    private val queueManager = QueueManager(context)

    /**
     * Main entry point. When Wi-Fi is available: reconcile all DOWNLOADING
     * items with the real DownloadManager state, then start the next QUEUED
     * item if the queue is idle. When Wi-Fi is unavailable: actually stop the
     * active download (remove the system request) and leave the queue waiting.
     */
    suspend fun processQueue(wifiConnected: Boolean) {
        LOCK.withLock {
            if (!wifiConnected) {
                Log.d(TAG, "Wi-Fi not connected; stopping active download (waits for Wi-Fi)")
                haltActiveForNetworkLoss()
                return@withLock
            }

            val active = dao.getByStatus(DownloadStatus.DOWNLOADING).first()
            active.forEach { item ->
                queueManager.refreshStatus(item)
            }

            advanceIfIdle()
        }
    }

    /**
     * Called by DownloadReceiver when the system engine broadcasts that a job
     * finished. Reconciles exactly this record (success or failure), then
     * advances the queue if it is now idle. Safe after process death: it looks
     * up the record by DownloadManager job id.
     */
    suspend fun onDownloadCompleted(jobId: Long) {
        LOCK.withLock {
            val item = dao.getByDownloadManagerId(jobId)
            if (item != null && item.status == DownloadStatus.DOWNLOADING) {
                queueManager.refreshStatus(item)
            }
            advanceIfIdle()
        }
    }

    /** User pause (also used by the Queue screen Pause button). */
    suspend fun pauseDownload(item: com.wifiqueue.app.data.DownloadItem) {
        LOCK.withLock { queueManager.pauseDownload(item) }
    }

    /** User resume: requeues; the next processQueue call does the start. */
    suspend fun resumeDownload(item: com.wifiqueue.app.data.DownloadItem) {
        LOCK.withLock { queueManager.resumeDownload(item) }
    }

    /** Cancel: removes the live request plus partial data; skips if terminal. */
    suspend fun cancelDownload(item: com.wifiqueue.app.data.DownloadItem) {
        LOCK.withLock { queueManager.cancelDownload(item) }
    }

    private suspend fun haltActiveForNetworkLoss() {
        dao.getByStatus(DownloadStatus.DOWNLOADING).first().forEach { item ->
            queueManager.stopForNetworkLoss(item)
        }
    }

    private suspend fun advanceIfIdle() {
        // One download at a time: only start when nothing is active.
        val active = dao.getByStatus(DownloadStatus.DOWNLOADING).first()
        if (active.isNotEmpty()) return

        val next = dao.getByStatus(DownloadStatus.QUEUED).first().firstOrNull()
        if (next != null) {
            Log.d(TAG, "Starting next queued download: ${next.title}")
            queueManager.startDownload(next)
        }
    }

    companion object {
        private const val TAG = "QueueSequencer"

        /** Process-wide lock shared by every QueueSequencer instance. */
        private val LOCK = Mutex()
    }
}
