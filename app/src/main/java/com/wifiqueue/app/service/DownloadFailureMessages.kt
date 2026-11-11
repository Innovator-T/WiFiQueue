package com.wifiqueue.app.service

import android.app.DownloadManager

/**
 * Maps raw Android DownloadManager codes to user-readable messages so failed or
 * stalled downloads explain what actually happened instead of just showing a
 * status. The raw code is included whenever it is not covered by a known
 * constant, so an unfamiliar ROM behaviour is still diagnosable.
 */
object DownloadFailureMessages {

    /**
     * [reason] is COLUMN_REASON for a STATUS_FAILED job. DownloadManager puts
     * either one of the ERROR_* codes or the raw HTTP status code in this
     * column, so both ranges are handled.
     */
    fun forReason(reason: Int): String {
        return when (reason) {
            in 400..599 ->
                "The server returned HTTP $reason and did not deliver the file."

            DownloadManager.ERROR_INSUFFICIENT_SPACE ->
                "Not enough free storage space to finish the download."

            DownloadManager.ERROR_FILE_ALREADY_EXISTS ->
                "A file with the same name already exists."

            DownloadManager.ERROR_FILE_ERROR ->
                "The file could not be written to storage."

            DownloadManager.ERROR_DEVICE_NOT_FOUND ->
                "The storage device is not available."

            DownloadManager.ERROR_UNHANDLED_HTTP_CODE ->
                "The server responded in an unexpected way."

            DownloadManager.ERROR_HTTP_DATA_ERROR ->
                "The connection was interrupted while receiving data."

            DownloadManager.ERROR_TOO_MANY_REDIRECTS ->
                "The server redirected too many times. The URL may not be a direct file."

            DownloadManager.ERROR_CANNOT_RESUME ->
                "The interrupted download could not be continued and must restart."

            else ->
                "The download failed (system reason $reason). Check the URL and your connection, then retry."
        }
    }

    /**
     * [reason] is COLUMN_REASON for a STATUS_PAUSED job. These mean Android
     * itself is holding the request back, which is why the app shows it
     * instead of pretending the transfer is still running.
     */
    fun forPauseReason(reason: Int): String {
        return when (reason) {
            DownloadManager.PAUSED_WAITING_TO_RETRY ->
                "Android is waiting before retrying the transfer (pause reason 1: waiting to retry)."

            DownloadManager.PAUSED_WAITING_FOR_NETWORK ->
                "Android is waiting for a usable network (pause reason 2: waiting for network)."

            DownloadManager.PAUSED_QUEUED_FOR_WIFI ->
                "Android is holding this download until an acceptable Wi-Fi network is available " +
                    "(pause reason 3: queued for Wi-Fi)."

            DownloadManager.PAUSED_UNKNOWN ->
                "Android paused this download for an unknown reason (pause reason 4)."

            else ->
                "Android paused this download (pause reason $reason)."
        }
    }

    /** Stable name for COLUMN_STATUS, for logs and on-screen diagnostics. */
    fun describeStatus(status: Int): String {
        return when (status) {
            DownloadManager.STATUS_PENDING -> "PENDING"
            DownloadManager.STATUS_RUNNING -> "RUNNING"
            DownloadManager.STATUS_PAUSED -> "PAUSED"
            DownloadManager.STATUS_SUCCESSFUL -> "SUCCESSFUL"
            DownloadManager.STATUS_FAILED -> "FAILED"
            else -> "UNKNOWN"
        }
    }
}
