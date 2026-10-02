package com.wifiqueue.app.service

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.wifiqueue.app.data.AppDatabase
import com.wifiqueue.app.data.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId == -1L) return

        val database = AppDatabase.getDatabase(context)
        val helper = DownloadNotificationHelper(context)

        CoroutineScope(Dispatchers.IO).launch {
            val item = database.downloadDao().getAll().first().firstOrNull { it.downloadManagerId == downloadId }
            if (item != null) {
                database.downloadDao().update(
                    item.copy(
                        status = DownloadStatus.COMPLETED,
                        progress = 100,
                        completedAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        downloadManagerId = null
                    )
                )
                helper.showDownloadComplete(item.id, item.title)
            }
        }
    }

    companion object {
        fun register(context: Context) {
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(DownloadReceiver(), filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(DownloadReceiver(), filter)
            }
        }
    }
}
