package com.wifiqueue.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wifiqueue.app.data.AppSettings
import com.wifiqueue.app.data.StorageInfo

@Composable
fun SettingsScreen(
    settings: AppSettings,
    storageInfo: StorageInfo?,
    onWifiOnlyChange: (Boolean) -> Unit,
    onAutoResumeChange: (Boolean) -> Unit,
    onDownloadOvernightChange: (Boolean) -> Unit,
    onRefreshStorage: () -> Unit
) {
    // Storage is read from the device when the tab opens, not on every tick.
    LaunchedEffect(Unit) { onRefreshStorage() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingRow(
                    title = "Wi‑Fi only mode",
                    description = if (settings.wifiOnly) {
                        "Downloads run only on Wi‑Fi, never on mobile data."
                    } else {
                        "Downloads may continue on mobile data."
                    },
                    checked = settings.wifiOnly,
                    onCheckedChange = onWifiOnlyChange
                )

                SettingRow(
                    title = "Auto resume",
                    description = if (settings.autoResume) {
                        "A download stopped by a lost connection restarts automatically."
                    } else {
                        "A stopped download waits until you tap Resume."
                    },
                    checked = settings.autoResume,
                    onCheckedChange = onAutoResumeChange
                )

                SettingRow(
                    title = "Download overnight",
                    description = if (settings.downloadOvernight) {
                        "Queued downloads start only between " +
                            "${AppSettings.OVERNIGHT_START_HOUR}:00 and " +
                            "${AppSettings.OVERNIGHT_END_HOUR}:00."
                    } else {
                        "Queued downloads start at any time."
                    },
                    checked = settings.downloadOvernight,
                    onCheckedChange = onDownloadOvernightChange
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Storage overview", fontWeight = FontWeight.Bold)

                val info = storageInfo
                if (info == null) {
                    Text("Reading device storage…")
                } else {
                    Text("Used: ${formatBytes(info.usedBytes)}")
                    Text("Available: ${formatBytes(info.availableBytes)}")
                    Text("Total: ${formatBytes(info.totalBytes)}")
                    Text(
                        "Queue requires: ${formatBytes(info.queueRequiredBytes)}" +
                            if (info.queueItemsWithUnknownSize > 0) {
                                " (+${info.queueItemsWithUnknownSize} with size not reported yet)"
                            } else {
                                ""
                            }
                    )
                    Text(
                        "Status: " + if (info.hasSufficientSpace) {
                            "Sufficient storage"
                        } else {
                            "Not enough free space for the current queue"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
