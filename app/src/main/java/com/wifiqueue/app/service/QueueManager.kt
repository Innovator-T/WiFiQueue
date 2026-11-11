package com.wifiqueue.app.service

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
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
     * How long a job Android accepted but never started (still PENDING, or
     * PAUSED, with 0 bytes transferred) may hold the sequential queue before
     * it is released. Generous enough for a normal start, short enough that the
     * queue cannot stay wedged behind it.
     */
    private val startTimeoutMs = 90_000L

    /**
     * Same guard for a job that did transfer data but then stopped making
     * progress (for example waiting-to-retry backoff after an interruption).
     * Longer than [startTimeoutMs] so a legitimate system retry is not
     * discarded, but still finite so the sequential queue cannot hang on it.
     */
    private val stallTimeoutMs = 10 * 60_000L

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
                    downloadedBytes = 0,
                    systemState = null,
                    storagePath = file.absolutePath,
                    downloadManagerId = downloadId,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis()
                )
            )
            notificationHelper.showDownloadProgress(fresh.id, fresh.title, 0, 0, 0)
            Log.i(TAG, "Enqueued dm=$downloadId for item ${fresh.id}; awaiting the first system status report")
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
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val downloaded = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            ).coerceAtLeast(0L)
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val source = cursor.stringOrNull(DownloadManager.COLUMN_URI)
            val localUri = cursor.stringOrNull(DownloadManager.COLUMN_LOCAL_URI)

            // Diagnostic trail for every state Android reports. The source URL
            // is reduced to scheme://host so no complete URL, file path or
            // query parameter (possibly a signed token) reaches logcat.
            Log.i(
                TAG,
                "dm=$downloadId item=${item.id} status=${DownloadFailureMessages.describeStatus(status)}" +
                    "($status) reason=$reason downloaded=$downloaded total=$total" +
                    " src=${redactSource(source)} dst=$localUri"
            )

            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    dao.update(
                        item.copy(
                            status = DownloadStatus.COMPLETED,
                            progress = 100,
                            downloadedBytes = if (total > 0L) total else downloaded,
                            fileSizeBytes = if (total > 0L) total else item.fileSizeBytes,
                            completedAt = System.currentTimeMillis(),
                            downloadManagerId = null,
                            systemState = null,
                            errorMessage = null,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    notificationHelper.showDownloadComplete(item.id, item.title)
                    Log.d(TAG, "Download completed: item=${item.id} dm=$downloadId")
                }

                DownloadManager.STATUS_FAILED -> {
                    val message = DownloadFailureMessages.forReason(reason)
                    Log.w(TAG, "dm=$downloadId failed: reason=$reason -> $message")
                    markFailed(item, message)
                }

                DownloadManager.STATUS_PENDING ->
                    reportSystemState(
                        item = item,
                        status = status,
                        reason = reason,
                        downloaded = downloaded,
                        total = total,
                        note = "Android has accepted this request but has not started it yet."
                    )

                DownloadManager.STATUS_PAUSED ->
                    reportSystemState(
                        item = item,
                        status = status,
                        reason = reason,
                        downloaded = downloaded,
                        total = total,
                        note = DownloadFailureMessages.forPauseReason(reason)
                    )

                DownloadManager.STATUS_RUNNING ->
                    reportSystemState(
                        item = item,
                        status = status,
                        reason = reason,
                        downloaded = downloaded,
                        total = total,
                        note = null
                    )

                else ->
                    reportSystemState(
                        item = item,
                        status = status,
                        reason = reason,
                        downloaded = downloaded,
                        total = total,
                        note = "Android reported an unrecognised download state."
                    )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reconciling item ${item.id}", e)
        } finally {
            cursor.close()
        }
    }

    /**
     * Persists what Android actually reported for an in-flight job (real bytes,
     * real total, and — for pending/paused — the reason) and releases a job that
     * Android accepted but never started, so one stuck request cannot block the
     * sequential queue forever.
     *
     * [note] is null for a healthy running transfer, so ordinary progress does
     * not clutter the queue with diagnostics.
     */
    private suspend fun reportSystemState(
        item: DownloadItem,
        status: Int,
        reason: Int,
        downloaded: Long,
        total: Long,
        note: String?
    ) {
        val now = System.currentTimeMillis()
        val progress = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
        val fileSize = if (total > 0L) total else item.fileSizeBytes
        val systemState = note?.let {
            "$it (Android status ${DownloadFailureMessages.describeStatus(status)}, reason $reason)"
        }

        // updatedAt only advances when real bytes move, so the stall check below
        // measures transfer progress rather than how often this method ran.
        val bytesMoved = downloaded != item.downloadedBytes
        val changed = bytesMoved || progress != item.progress ||
            fileSize != item.fileSizeBytes || systemState != item.systemState

        if (changed) {
            dao.update(
                item.copy(
                    progress = progress,
                    downloadedBytes = downloaded,
                    fileSizeBytes = fileSize,
                    systemState = systemState,
                    updatedAt = if (bytesMoved) now else item.updatedAt
                )
            )
            if (bytesMoved) {
                notificationHelper.showDownloadProgress(
                    item.id,
                    item.title,
                    progress,
                    downloaded,
                    fileSize
                )
            }
        }

        // The moment progress last happened. If bytes moved in this very
        // report, the item just resumed and the clock restarts at now; using
        // the stale item.updatedAt here would falsely release a transfer that
        // had only just resumed after a long pause.
        val lastProgressAt = if (bytesMoved) now else item.updatedAt

        // A request that never starts, or one that stopped transferring, must
        // not hold the sequential queue forever. The record itself is kept
        // (PAUSED + Resume); only the dead system request is removed.
        val pendingOrPaused = status == DownloadManager.STATUS_PENDING ||
            status == DownloadManager.STATUS_PAUSED
        val timeout = if (downloaded == 0L) startTimeoutMs else stallTimeoutMs
        val stalled = pendingOrPaused && now - lastProgressAt >= timeout
        if (!stalled) return

        val waitedSeconds = ((now - lastProgressAt) / 1_000L).coerceAtLeast(0L)
        val message = buildString {
            append("Android transferred no data for ")
            append(waitedSeconds)
            append("s (")
            append(DownloadFailureMessages.describeStatus(status))
            append(", reason ")
            append(reason)
            append("). ")
            note?.let { append(it).append(' ') }
            if (downloaded > 0L) {
                append("The partial data ($downloaded bytes) was discarded, because DownloadManager cannot continue it. ")
            }
            append("The system request was removed so the queue could continue. Tap Resume to retry.")
        }
        Log.w(TAG, "Releasing stalled dm=${item.downloadManagerId} item=${item.id}: $message")

        item.downloadManagerId?.let { removeRequest(it) }
        val paused = DownloadStateTransition.validated(item.status, DownloadStatus.PAUSED) ?: return
        dao.update(
            item.copy(
                status = paused,
                progress = 0,
                downloadedBytes = 0L,
                downloadManagerId = null,
                systemState = message,
                errorMessage = null,
                updatedAt = now
            )
        )
        storageManager.deleteFileQuietly(item.storagePath)
        notificationHelper.cancel(item.id)
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
                downloadedBytes = 0,
                systemState = null,
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
                downloadedBytes = 0,
                systemState = null,
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
                downloadedBytes = 0,
                systemState = null,
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
                downloadedBytes = 0,
                systemState = null,
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
            downloadedBytes = 0,
            systemState = null,
            downloadManagerId = null,
            errorMessage = message,
            updatedAt = System.currentTimeMillis()
        )
        dao.update(updated)
        storageManager.deleteFileQuietly(item.storagePath)
        notificationHelper.showDownloadFailed(item.id, item.title)
    }

    /** Reads an optional DownloadManager column, tolerating ROMs without it. */
    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    /**
     * Reduces a download URL to scheme://host before it reaches logcat, so
     * signed paths and query parameters are never written to the log.
     */
    private fun redactSource(raw: String?): String {
        if (raw.isNullOrBlank()) return "unknown"
        return try {
            val uri = Uri.parse(raw)
            val scheme = uri.scheme ?: return "opaque"
            val host = uri.host ?: uri.authority?.substringAfterLast('@') ?: return scheme
            "$scheme://$host"
        } catch (e: Exception) {
            "unparseable"
        }
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
