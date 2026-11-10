package com.wifiqueue.app.service

import android.content.Context
import android.os.Environment
import java.io.File

class StorageManager(private val context: Context) {
    fun getDownloadDirectory(): File {
        return context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
    }

    fun getAvailableStorageBytes(): Long {
        val directory = getDownloadDirectory()
        return if (directory.exists()) {
            val stat = android.os.StatFs(directory.absolutePath)
            stat.availableBytes
        } else {
            0L
        }
    }

    fun getTotalStorageBytes(): Long {
        val directory = getDownloadDirectory()
        return if (directory.exists()) {
            val stat = android.os.StatFs(directory.absolutePath)
            stat.totalBytes
        } else {
            0L
        }
    }

    fun getUsedStorageBytes(): Long = getTotalStorageBytes() - getAvailableStorageBytes()

    fun hasEnoughSpace(requiredBytes: Long): Boolean = getAvailableStorageBytes() > requiredBytes

    /**
     * Creates a destination file for a new download. If a file with the requested
     * name already exists on disk (for example from an earlier download with the
     * same title), a numbered variant ("Name (1).ext") is returned instead, so
     * two downloads can never target the same destination path.
     */
    fun createUniqueDownloadFile(requestedFileName: String): File {
        val directory = getDownloadDirectory()
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val safeName = requestedFileName.ifBlank { "download" }
        val base = File(directory, safeName)
        if (!base.exists()) {
            return base
        }
        val dot = safeName.lastIndexOf('.')
        val stem = if (dot > 0) safeName.substring(0, dot) else safeName
        val extension = if (dot > 0) safeName.substring(dot) else ""
        var index = 1
        var candidate: File
        do {
            candidate = File(directory, "$stem ($index)$extension")
            index++
        } while (candidate.exists())
        return candidate
    }

    /**
     * Deletes a partial or obsolete destination file. Never throws: a failed
     * cleanup must not change the download's recorded state.
     */
    fun deleteFileQuietly(path: String?) {
        if (path.isNullOrBlank()) return
        try {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: SecurityException) {
            // Leave the file in place; failure logging is handled by callers.
        }
    }
}
