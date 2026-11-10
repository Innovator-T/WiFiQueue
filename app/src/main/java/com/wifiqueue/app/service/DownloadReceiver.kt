package com.wifiqueue.app.service

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives ACTION_DOWNLOAD_COMPLETE from the system DownloadManager.
 *
 * The broadcast only says WHOSE job id finished, not whether it succeeded, so
 * this receiver hands the job id to QueueSequencer, which reconciles the real
 * DownloadManager state (success/failure/progress) and then advances the
 * queue. goAsync() keeps the receiver alive while the database work runs.
 */
class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId == -1L) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sequencer = QueueSequencer(context.applicationContext)
                sequencer.onDownloadCompleted(downloadId)
            } catch (e: Exception) {
                // Even if reconciliation fails here, the periodic worker and
                // UI polling reconcile the same record later.
                QueueProcessorWorker.enqueueNow(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
