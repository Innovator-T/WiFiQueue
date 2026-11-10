package com.wifiqueue.app.data

enum class DownloadStatus {
    WAITING,
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Single source of truth for legal state transitions. Every status write in the
 * engine (QueueManager) goes through this table, so the database can never
 * record a state the download lifecycle does not actually pass through.
 */
object DownloadStateMachine {

    /**
     * Returns true when moving a download from [from] to [to] is allowed.
     * DOWNLOADING -> QUEUED is the "stopped safely, will restart when ready"
     * transition used when Wi-Fi is lost (QueueManager.stopForNetworkLoss).
     */
    fun canTransition(from: DownloadStatus, to: DownloadStatus): Boolean {
        return when (from) {
            DownloadStatus.WAITING ->
                to == DownloadStatus.QUEUED || to == DownloadStatus.CANCELLED

            DownloadStatus.QUEUED ->
                to == DownloadStatus.DOWNLOADING || to == DownloadStatus.WAITING ||
                    to == DownloadStatus.PAUSED || to == DownloadStatus.FAILED ||
                    to == DownloadStatus.CANCELLED

            DownloadStatus.DOWNLOADING ->
                to == DownloadStatus.COMPLETED || to == DownloadStatus.FAILED ||
                    to == DownloadStatus.PAUSED || to == DownloadStatus.QUEUED ||
                    to == DownloadStatus.CANCELLED

            DownloadStatus.PAUSED ->
                to == DownloadStatus.QUEUED || to == DownloadStatus.CANCELLED

            DownloadStatus.FAILED ->
                to == DownloadStatus.QUEUED || to == DownloadStatus.CANCELLED

            DownloadStatus.CANCELLED ->
                to == DownloadStatus.QUEUED

            DownloadStatus.COMPLETED ->
                false
        }
    }

    fun isActive(status: DownloadStatus): Boolean = status == DownloadStatus.DOWNLOADING

    fun isTerminal(status: DownloadStatus): Boolean = status == DownloadStatus.COMPLETED
}
