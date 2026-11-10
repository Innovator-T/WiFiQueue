package com.wifiqueue.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM download_items ORDER BY createdAt ASC")
    fun getAll(): Flow<List<DownloadItem>>

    @Query("SELECT * FROM download_items WHERE id = :id")
    suspend fun getById(id: Long): DownloadItem?

    /** Finds the download currently owning a DownloadManager job id. */
    @Query("SELECT * FROM download_items WHERE downloadManagerId = :downloadManagerId LIMIT 1")
    suspend fun getByDownloadManagerId(downloadManagerId: Long): DownloadItem?

    @Query("SELECT * FROM download_items WHERE status = :status ORDER BY createdAt ASC")
    fun getByStatus(status: DownloadStatus): Flow<List<DownloadItem>>

    /** Queued downloads (convenience query over getByStatus). */
    @Query("SELECT * FROM download_items WHERE status = :status ORDER BY createdAt ASC")
    fun getQueued(status: DownloadStatus): Flow<List<DownloadItem>>

    /** Active (in progress) downloads. */
    @Query("SELECT * FROM download_items WHERE status = :status ORDER BY createdAt ASC")
    fun getActive(status: DownloadStatus): Flow<List<DownloadItem>>

    /** Completed downloads, most recent first. */
    @Query("SELECT * FROM download_items WHERE status = :status ORDER BY completedAt DESC")
    fun getCompleted(status: DownloadStatus): Flow<List<DownloadItem>>

    /** Failed and cancelled downloads available for retry. */
    @Query("SELECT * FROM download_items WHERE status = :failed OR status = :cancelled ORDER BY createdAt ASC")
    fun getRetryable(failed: DownloadStatus, cancelled: DownloadStatus): Flow<List<DownloadItem>>

    @Query(
        "UPDATE download_items SET status = :status, progress = :progress, " +
        "fileSizeBytes = :fileSizeBytes, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateProgress(
        id: Long,
        status: DownloadStatus,
        progress: Int,
        fileSizeBytes: Long,
        updatedAt: Long
    )

    @Query("UPDATE download_items SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: DownloadStatus, updatedAt: Long)

    @Update
    suspend fun update(item: DownloadItem)

    @Insert
    suspend fun insert(item: DownloadItem): Long

    @Delete
    suspend fun delete(item: DownloadItem)
}
