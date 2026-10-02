package com.wifiqueue.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wifiqueue.app.data.DownloadItem
import com.wifiqueue.app.data.DownloadStatus
import com.wifiqueue.app.ui.screens.HistoryScreen
import com.wifiqueue.app.ui.screens.HomeScreen
import com.wifiqueue.app.ui.screens.QueueScreen
import com.wifiqueue.app.ui.screens.SettingsScreen
import com.wifiqueue.app.ui.theme.WifiQueueTheme
import com.wifiqueue.app.util.ConnectivityMonitor
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
    val wifiConnected by viewModel.wifiConnected.collectAsState()
    val tabs = listOf("Home", "Queue", "History", "Settings")
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val connectivityMonitor = ConnectivityMonitor(androidx.compose.ui.platform.LocalContext.current)
    LaunchedEffect(Unit) {
        connectivityMonitor.observeWifiState().collect { connected ->
            viewModel.updateWifiState(connected)
        }
    }

    val queueList = downloads.filter { it.status != DownloadStatus.COMPLETED }
    val historyList = downloads.filter { it.status == DownloadStatus.COMPLETED }

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

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = index == selectedTab,
                        onClick = { selectedTab = index },
                        icon = {
                            val icon = when (title) {
                                "Home" -> Icons.Default.Home
                                "Queue" -> Icons.Default.Queue
                                "History" -> Icons.Default.History
                                else -> Icons.Default.Settings
                            }
                            Icon(icon, contentDescription = title)
                        },
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier.padding(paddingValues)
        ) {
            when (selectedTab) {
                0 -> HomeScreen(
                    wifiConnected = wifiConnected,
                    onAddDownload = onAddDownload
                )
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
