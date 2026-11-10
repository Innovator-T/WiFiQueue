package com.wifiqueue.app.service

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.wifiqueue.app.data.AppDatabase
import com.wifiqueue.app.data.DownloadItem
import com.wifiqueue.app.data.DownloadStateMachine
import com.wifiqueue.app.data.DownloadStatus

/**
 * Owns every direct interaction with the Android DownloadManager for one
 * download item. All calls are serialized by QueueSequencer's global mutex,
 * so no two code paths can start/stop the same download at the same time.
 *
 * Pause/strategy note: DownloadManager has no public API to pause an
 * individual download. Pausing therefore removes the active request (which
 * also deletes its partial data), and resuming re-enqueues a fresh request
 * that starts from 0 bytes. The UI explains this honestly instead of
 * claiming a byte-level resume that never happened.
 */
class QueueManager(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val dao = database.downloadDao()
    private val storageManager = StorageManager(context)
    private val notificationHelper = DownloadNotificationHelper(context)
    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** Fallback free-space floor used until the real file size is known. */
    private val minimumFreeSpaceBytes = 50L * 1_024L * 1_024L

    /**
     * Enqueues a fresh DownloadManager request for [item]. The caller
     * (QueueSequencer) only invokes this for a QUEUED download, so only one
     * item can become active, and inside the sequencer's lock so no second
     * worker can start the same item twice.
     */
    suspend fun startDownload(item: DownloadItem) {
        // Re-read from the database: a stale in-memory item may have been
        // cancelled or already started by another code path in the meantime.
        val fresh = dao.getById(item.id)
        if (fresh == null || fresh.status != DownloadStatus.QUEUED) {
            Log.d(TAG, "Skipping start; item ${item.id} is not QUEUED (was ${item.status})")
            return
        }

        val requiredBytes = if (fresh.fileSizeBytes > 0L) fresh.fileSizeBytes else minimumFreeSpaceBytes
        if (!storageManager.hasEnoughSpace(requiredBytes)) {
            markFailed(fresh, "Not enough free storage space to start this download.")
            return
        }

        try {
            // Safety net: never leave a stale DownloadManager job attached while
            // enqueueing a replacement request for the same record.
            fresh.downloadManagerId?.let { removeRequest(it) }

            val file = storageManager.createUniqueDownloadFile(fresh.fileName)
            val request = DownloadManager.Request(Uri.parse(fresh.url))
                .setTitle(fresh.title)
                .setDescription(fresh.url)
                // Wi-Fi-only is enforced by the system engine itself, so queued
                // downloads can never consume mobile data.
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(file))

            val downloadId = downloadManager.enqueue(request)
            dao.update(
                fresh.copy(
                    status = DownloadStatus.DOWNLOADING,
                    progress = 0,
                    storagePath = file.absolutePath,
                    downloadManagerId = downloadId,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis()
                )
            )
            notificationHelper.showDownloadProgress(fresh.id, fresh.title, 0, 0)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid download URL for item ${fresh.id}", e)
            markFailed(fresh, "This URL is not a valid direct download link.")
        } catch (e: SecurityException) {
            Log.e(TAG, "DownloadManager rejected the request for item ${fresh.id}", e)
            markFailed(fresh, "The system refused this download request.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue download ${fresh.id}", e)
            markFailed(fresh, "Could not start the download: ${e.message ?: "unknown error"}")
        }
    }

    /**
     * Reconciles a DOWNLOADING record with the real state of its active
     * DownloadManager job: updates progress, and completes/fails the record
     * when the system engine finished or failed the job.
     */
    suspend fun refreshStatus(item: DownloadItem) {
        val downloadId = item.downloadManagerId
            ?: run {
                markFailed(item, "The download reference was lost. Tap Retry to restart it.")
                return
            }

        val cursor = try {
            downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
        } catch (e: Exception) {
            Log.e(TAG, "DownloadManager query failed for item ${item.id}", e)
            markFailed(item, "The download could not be checked. Tap Retry.")
            return
        }

        if (cursor == null || !cursor.moveToFirst()) {
            cursor?.close()
            markFailed(item, "This download no longer exists in the system. Tap Retry.")
            return
        }

        try {
            val statusIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val downloadedIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val status = cursor.getInt(statusIndex)
            val downloaded = cursor.getLong(downloadedIndex)
            val total = cursor.getLong(totalIndex)
            val progress = if (total > 0L) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0

            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    dao.update(
                        item.copy(
                            status = DownloadStatus.COMPLETED,
                            progress = 100,
                            fileSizeBytes = if (total > 0L) total else item.fileSizeBytes,
                            completedAt = System.currentTimeMillis(),
                            downloadManagerId = null,
                            errorMessage = null,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    notificationHelper.showDownloadComplete(item.id, item.title)
                    Log.d(TAG, "Download completed: ${item.title}")
                }

                DownloadManager.STATUS_FAILED -> {
                    val reasonIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                    val message = DownloadFailureMessages.forReason(cursor.getInt(reasonIndex))
                    markFailed(item, message)
                }

                else -> {
                    // STATUS_PENDING, STATUS_RUNNING or STATUS_PAUSED (system-
                    // level retry/waiting): keep DOWNLOADING with fresh progress.
                    val fileSize = if (total > 0L) total else item.fileSizeBytes
                    if (progress != item.progress || fileSize != item.fileSizeBytes) {
                        dao.update(
                            item.copy(
                                progress = progress,
                                fileSizeBytes = fileSize,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        notificationHelper.showDownloadProgress(item.id, item.title, progress, fileSize)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reconciling item ${item.id}", e)
        } finally {
            cursor.close()
        }
    }

    /**
     * User pause: removes the live request and its partial data, records the
     * honest state, and clears the progress that no longer exists on disk.
     */
    suspend fun pauseDownload(item: DownloadItem) {
        val fresh = dao.getById(item.id) ?: item
        if (DownloadStateMachine.isTerminal(fresh.status)) return

        fresh.downloadManagerId?.let { removeRequest(it) }
        storageManager.deleteFileQuietly(fresh.storagePath)

        dao.update(
            fresh.copy(
                status = DownloadStatus.PAUSED,
                progress = 0,
                downloadManagerId = null,
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
        )
        notificationHelper.cancel(fresh.id)
    }

    /**
     * Resume after user pause or Wi-Fi loss: clears the old job/progress and
     * re-queues the item. The actual start is left to QueueSequencer so the
     * queue stays sequential and no duplicate request is created. If the
     * download had already made progress, the message tells the user the
     * restart begins from 0 bytes (DownloadManager limitation).
     */
    suspend fun resumeDownload(item: DownloadItem) {
        val fresh = dao.getById(item.id) ?: return
        if (DownloadStateMachine.isTerminal(fresh.status)) return

        fresh.downloadManagerId?.let { removeRequest(it) }
        storageManager.deleteFileQuietly(fresh.storagePath)

        val hadProgress = fresh.progress > 0
        val message = if (hadProgress) {
            "Restarted from the beginning: the previous partial data could not be continued."
        } else {
            null
        }

        dao.update(
            fresh.copy(
                status = DownloadStatus.QUEUED,
                progress = 0,
                downloadManagerId = null,
                errorMessage = message,
                updatedAt = System.currentTimeMillis()
            )
        )
        notificationHelper.cancel(fresh.id)
    }

    /**
     * Wi-Fi loss handling: the live request is actually removed from the
     * DownloadManager (not just flagged in the database), the partial file is
     * cleaned up, and the item returns to the queue so it restarts
     * automatically the next time the sequencer runs with Wi-Fi connected.
     */
    suspend fun stopForNetworkLoss(item: DownloadItem) {
        val fresh = dao.getById(item.id) ?: item
        if (fresh.status != DownloadStatus.DOWNLOADING) return

        fresh.downloadManagerId?.let { removeRequest(it) }
        storageManager.deleteFileQuietly(fresh.storagePath)

        dao.update(
            fresh.copy(
                status = DownloadStatus.QUEUED,
                progress = 0,
                downloadManagerId = null,
                errorMessage = "Wi-Fi was disconnected. It will restart automatically when Wi-Fi returns.",
                updatedAt = System.currentTimeMillis()
            )
        )
        notificationHelper.cancel(fresh.id)
    }

    /**
     * Cancel: removes the live request (if any), deletes the partial data for
     * THIS item only, and clears the DownloadManager link so no stale job can
     * ever be attached to this record again.
     */
    suspend fun cancelDownload(item: DownloadItem) {
        val fresh = dao.getById(item.id) ?: item
        if (DownloadStateMachine.isTerminal(fresh.status)) return

        fresh.downloadManagerId?.let { removeRequest(it) }
        // Delete only the partial data of this record, never files belonging
        // to other (completed) downloads.
        storageManager.deleteFileQuietly(fresh.storagePath)

        dao.update(
            fresh.copy(
                status = DownloadStatus.CANCELLED,
                progress = 0,
                downloadManagerId = null,
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
        )
        notificationHelper.cancel(fresh.id)
    }

    /** Shared failure path: verified transition + message + notification. */
    private suspend fun markFailed(item: DownloadItem, message: String) {
        val updated = item.copy(
            status = DownloadStateTransition.validated(item.status, DownloadStatus.FAILED)
                ?: return,
            downloadManagerId = null,
            errorMessage = message,
            updatedAt = System.currentTimeMillis()
        )
        dao.update(updated)
        storageManager.deleteFileQuietly(item.storagePath)
        notificationHelper.showDownloadFailed(item.id, item.title)
    }

    private fun removeRequest(downloadId: Long) {
        try {
            downloadManager.remove(downloadId)
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove DownloadManager job $downloadId", e)
        }
    }

    /**
     * Small helper that applies the transition table. Returns the target
     * status only when the transition is legal, otherwise null.
     */
    private object DownloadStateTransition {
        fun validated(from: DownloadStatus, to: DownloadStatus): DownloadStatus? {
            return if (DownloadStateMachine.canTransition(from, to)) to else null
        }
    }

    companion object {
        private const val TAG = "QueueManager"
    }
}
