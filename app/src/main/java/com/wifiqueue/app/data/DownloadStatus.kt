package com.wifiqueue.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wifiqueue.app.data.DownloadItem
import com.wifiqueue.app.ui.screens.HistoryScreen
import com.wifiqueue.app.ui.screens.HomeScreen
import com.wifiqueue.app.ui.screens.QueueScreen
import com.wifiqueue.app.ui.screens.SettingsScreen
import com.wifiqueue.app.ui.theme.WifiQueueTheme
import com.wifiqueue.app.viewmodel.DownloadViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WifiQueueTheme {
                WifiQueueAppScreen()
            }
        }
    }
}

@Composable
fun WifiQueueAppScreen(viewModel: DownloadViewModel = viewModel()) {
    val downloads by viewModel.downloads.collectAsState(initial = emptyList())
    val tabs = listOf("Home", "Queue", "History", "Settings")
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val queueList = downloads.filter { it.status != com.wifiqueue.app.data.DownloadStatus.COMPLETED }
    val historyList = downloads.filter { it.status == com.wifiqueue.app.data.DownloadStatus.COMPLETED }

    val onAddDownload: (String, String) -> Unit = { title, url ->
        if (title.isNotBlank() && url.isNotBlank()) {
            viewModel.addDownload(title.trim(), url.trim())
            selectedTab = 1
        }
    }

    val onToggleDownload: (DownloadItem) -> Unit = { item ->
        viewModel.toggleDownload(item)
    }

    val onDeleteDownload: (DownloadItem) -> Unit = { item ->
        viewModel.deleteDownload(item)
    }

    androidx.compose.material3.Scaffold(
        bottomBar = {
            androidx.compose.material3.NavigationBar {
                tabs.forEachIndexed { index, title ->
                    androidx.compose.material3.NavigationBarItem(
                        selected = index == selectedTab,
                        onClick = { selectedTab = index },
                        icon = {
                            val icon = when (title) {
                                "Home" -> androidx.compose.material.icons.Icons.Default.Home
                                "Queue" -> androidx.compose.material.icons.Icons.Default.Queue
                                "History" -> androidx.compose.material.icons.Icons.Default.History
                                else -> androidx.compose.material.icons.Icons.Default.Settings
                            }
                            androidx.compose.material3.Icon(icon, contentDescription = title)
                        },
                        label = { androidx.compose.material3.Text(title) }
                    )
                }
            }
        }
    ) { paddingValues ->
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier
                .padding(paddingValues)
        ) {
            when (selectedTab) {
                0 -> HomeScreen(onAddDownload = onAddDownload)
                1 -> QueueScreen(
                    downloads = queueList,
                    onToggle = onToggleDownload,
                    onDelete = onDeleteDownload
                )
                2 -> HistoryScreen(downloads = historyList)
                3 -> SettingsScreen()
            }
        }
    }
}
