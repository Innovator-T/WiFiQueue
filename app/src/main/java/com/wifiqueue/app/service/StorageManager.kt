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

    fun createDownloadFile(fileName: String): File {
        val directory = getDownloadDirectory()
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return File(directory, fileName)
    }
}
