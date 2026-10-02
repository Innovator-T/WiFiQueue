package com.wifiqueue.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.wifiqueue.app.MainActivity

class DownloadNotificationHelper(private val context: Context) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WiFiQueue downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Notifications for queued and completed downloads"
            notificationManager?.createNotificationChannel(channel)
        }
    }

    fun showDownloadProgress(itemId: Long, title: String, progress: Int, totalBytes: Long) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            itemId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("${formatBytes(totalBytes * progress / 100L)} / ${formatBytes(totalBytes)}")
            .setProgress(100, progress, false)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        notificationManager?.notify(itemId.toInt(), builder.build())
    }

    fun showDownloadComplete(itemId: Long, title: String) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            itemId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download complete")
            .setContentText(title)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager?.notify(itemId.toInt(), builder.build())
    }

    fun showDownloadFailed(itemId: Long, title: String) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            itemId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download failed")
            .setContentText(title)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager?.notify(itemId.toInt(), builder.build())
    }

    fun cancel(itemId: Long) {
        notificationManager?.cancel(itemId.toInt())
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000L -> String.format("%.2f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000L -> String.format("%.2f MB", bytes / 1_000_000.0)
            bytes >= 1_000L -> String.format("%.2f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    companion object {
        const val CHANNEL_ID = "wifi_queue_downloads"
    }
}
