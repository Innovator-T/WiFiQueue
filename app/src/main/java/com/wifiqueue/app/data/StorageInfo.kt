package com.wifiqueue.app.data

/**
 * Snapshot of the download volume's real capacity, together with how much space
 * the current queue is expected to need. Values come from Android's StatFs and
 * from the sizes DownloadManager reported for the queued items.
 */
data class StorageInfo(
    val usedBytes: Long,
    val availableBytes: Long,
    val totalBytes: Long,
    /** Sum of the known sizes of unfinished downloads. */
    val queueRequiredBytes: Long,
    /** Unfinished downloads whose size Android has not reported yet. */
    val queueItemsWithUnknownSize: Int
) {
    /** True when the free space covers the part of the queue that is known. */
    val hasSufficientSpace: Boolean get() = availableBytes > queueRequiredBytes
}
