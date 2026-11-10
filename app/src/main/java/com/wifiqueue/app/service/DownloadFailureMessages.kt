package com.wifiqueue.app.service

import android.app.DownloadManager

/**
 * Maps Android DownloadManager failure reasons to user-readable messages so
 * failed downloads explain what went wrong instead of just showing a status.
 */
object DownloadFailureMessages {

    fun forReason(reason: Int): String {
        return when (reason) {
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

            DownloadManager.ERROR_BLOCKED ->
                "The download was blocked by the system."

            else ->
                "The download failed. Check the URL and your connection, then retry."
        }
    }
}
