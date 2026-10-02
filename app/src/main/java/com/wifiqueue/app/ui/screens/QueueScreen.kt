package com.wifiqueue.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifiqueue.app.data.DownloadItem
import com.wifiqueue.app.data.DownloadStatus

fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000L -> String.format("%.2f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format("%.2f MB", bytes / 1_000_000.0)
        bytes >= 1_000L -> String.format("%.2f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}

@Composable
fun QueueScreen(
    downloads: List<DownloadItem>,
    onToggle: (DownloadItem) -> Unit,
    onDelete: (DownloadItem) -> Unit
) {
    if (downloads.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Queue is empty")
            Text("Add downloads from Home to get started.")
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(downloads) { item ->
            DownloadItemCard(
                item = item,
                onToggle = { onToggle(item) },
                onDelete = { onDelete(item) }
            )
        }
    }
}

@Composable
fun DownloadItemCard(
    item: DownloadItem,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        getStatusLabel(item.status),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }

            if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.QUEUED) {
                LinearProgressIndicator(
                    progress = { item.progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${item.progress}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "${formatBytes((item.progress.toLong() * item.fileSizeBytes) / 100)} / ${formatBytes(item.fileSizeBytes)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth(),
                enabled = item.status != DownloadStatus.COMPLETED
            ) {
                Icon(
                    when (item.status) {
                        DownloadStatus.DOWNLOADING -> Icons.Default.Pause
                        else -> Icons.Default.PlayArrow
                    },
                    contentDescription = null
                )
                Text(
                    when (item.status) {
                        DownloadStatus.WAITING -> "Queue"
                        DownloadStatus.QUEUED -> "Queued"
                        DownloadStatus.DOWNLOADING -> "Pause"
                        DownloadStatus.PAUSED -> "Resume"
                        DownloadStatus.FAILED -> "Retry"
                        DownloadStatus.CANCELLED -> "Retry"
                        DownloadStatus.COMPLETED -> "Done"
                    }
                )
            }
        }
    }
}

private fun getStatusLabel(status: DownloadStatus): String {
    return when (status) {
        DownloadStatus.WAITING -> "Waiting to be queued"
        DownloadStatus.QUEUED -> "In queue, waiting for Wi‑Fi"
        DownloadStatus.DOWNLOADING -> "Downloading..."
        DownloadStatus.PAUSED -> "Paused – Wi‑Fi lost"
        DownloadStatus.COMPLETED -> "Completed"
        DownloadStatus.FAILED -> "Failed – tap Retry"
        DownloadStatus.CANCELLED -> "Cancelled"
    }
}
