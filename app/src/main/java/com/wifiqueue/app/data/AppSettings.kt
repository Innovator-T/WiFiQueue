package com.wifiqueue.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Settings shown on the Settings tab. They are persisted, and the queue engine
 * reads the same stored values, so a toggle changes behaviour immediately and
 * survives an app restart.
 */
data class AppSettings(
    /** true: requests are pinned to Wi-Fi; false: mobile data is allowed too. */
    val wifiOnly: Boolean = true,
    /** true: a download stopped by network loss restarts on its own. */
    val autoResume: Boolean = true,
    /** true: queued downloads only start inside the overnight window. */
    val downloadOvernight: Boolean = false
) {
    companion object {
        /** Start of the overnight window, inclusive (24h clock). */
        const val OVERNIGHT_START_HOUR = 22

        /** End of the overnight window, exclusive (24h clock). */
        const val OVERNIGHT_END_HOUR = 6

        /** Whether [hourOfDay] falls inside the overnight window (22:00-06:00). */
        fun isInsideOvernightWindow(hourOfDay: Int): Boolean =
            hourOfDay >= OVERNIGHT_START_HOUR || hourOfDay < OVERNIGHT_END_HOUR
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "wifiqueue_settings"
)

/** Reads and writes [AppSettings]; a single store shared by UI and engine. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val AUTO_RESUME = booleanPreferencesKey("auto_resume")
        val DOWNLOAD_OVERNIGHT = booleanPreferencesKey("download_overnight")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            wifiOnly = prefs[Keys.WIFI_ONLY] ?: defaults.wifiOnly,
            autoResume = prefs[Keys.AUTO_RESUME] ?: defaults.autoResume,
            downloadOvernight = prefs[Keys.DOWNLOAD_OVERNIGHT] ?: defaults.downloadOvernight
        )
    }

    suspend fun setWifiOnly(enabled: Boolean) = update { it[Keys.WIFI_ONLY] = enabled }

    suspend fun setAutoResume(enabled: Boolean) = update { it[Keys.AUTO_RESUME] = enabled }

    suspend fun setDownloadOvernight(enabled: Boolean) =
        update { it[Keys.DOWNLOAD_OVERNIGHT] = enabled }

    private suspend fun update(block: (MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { block(it) }
    }
}
