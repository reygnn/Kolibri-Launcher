package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.launcher.common.data.safePurge
import com.github.reygnn.launcher.common.data.readFlowFailOpen
import com.github.reygnn.nyx_launcher.home.model.IconStyle
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * DataStore-backed [PreferencesRepository] (shares the app's Preferences store).
 *
 * Every read flow goes through the shared [readFlowFailOpen]: a corrupt/unreadable store
 * (IOException) recovers to the per-key defaults instead of crashing the collector.
 */
class PreferencesRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : PreferencesRepository {

    override fun iconStyle(): Flow<IconStyle> =
        dataStore.readFlowFailOpen("Error reading iconStyle") { prefs ->
            // Prefer the tri-state key; migrate legacy boolean (monochrome_icons == true
            // → MONOCHROME) so existing users keep their setting. Unknown/absent → COLOR.
            // no suspension point — enum parse of a stored string.
            prefs[ICON_STYLE]?.let { runCatching { IconStyle.valueOf(it) }.getOrNull() }
                ?: if (prefs[MONOCHROME] == true) IconStyle.MONOCHROME else IconStyle.COLOR
        }

    override suspend fun setIconStyle(style: IconStyle) {
        dataStore.edit {
            it[ICON_STYLE] = style.name
            it.remove(MONOCHROME) // legacy boolean superseded by ICON_STYLE
        }
    }

    override fun searchAutoLaunch(): Flow<Boolean> =
        dataStore.readFlowFailOpen("Error reading searchAutoLaunch") { it[SEARCH_AUTO_LAUNCH] ?: false }

    override suspend fun setSearchAutoLaunch(enabled: Boolean) {
        dataStore.edit { it[SEARCH_AUTO_LAUNCH] = enabled }
    }

    override fun usageSortEnabled(): Flow<Boolean> =
        dataStore.readFlowFailOpen("Error reading usageSortEnabled") { it[USAGE_SORT] ?: false }

    override suspend fun setUsageSortEnabled(enabled: Boolean) {
        dataStore.edit { it[USAGE_SORT] = enabled }
    }

    override fun notificationDots(): Flow<Boolean> =
        dataStore.readFlowFailOpen("Error reading notificationDots") { it[NOTIFICATION_DOTS] ?: false }

    override suspend fun setNotificationDots(enabled: Boolean) {
        dataStore.edit { it[NOTIFICATION_DOTS] = enabled }
    }

    override val showAlarmFlow: Flow<Boolean> =
        dataStore.readFlowFailOpen("Error reading showAlarm") { it[SHOW_ALARM] ?: false }

    override suspend fun setShowAlarm(enabled: Boolean) {
        dataStore.edit { it[SHOW_ALARM] = enabled }
    }

    override val showCalendarEventFlow: Flow<Boolean> =
        dataStore.readFlowFailOpen("Error reading showCalendarEvent") { it[SHOW_CALENDAR_EVENT] ?: false }

    override suspend fun setShowCalendarEvent(enabled: Boolean) {
        dataStore.edit { it[SHOW_CALENDAR_EVENT] = enabled }
    }

    /**
     * Factory reset (2b-4c): removes this store's keys from the shared `home_layout` DataStore.
     * A failure is rethrown by `safePurge` (F1), so the reset reports it.
     */
    override suspend fun purgeRepository() {
        dataStore.safePurge("PreferencesRepositoryImpl") { preferences ->
            preferences.remove(ICON_STYLE)
            // The pre-tri-state boolean an upgraded user may still carry (2b-4c inventory).
            preferences.remove(MONOCHROME)
            preferences.remove(SEARCH_AUTO_LAUNCH)
            preferences.remove(USAGE_SORT)
            preferences.remove(NOTIFICATION_DOTS)
            preferences.remove(SHOW_ALARM)
            preferences.remove(SHOW_CALENDAR_EVENT)
        }
    }

    private companion object {
        val ICON_STYLE = stringPreferencesKey("icon_style")
        // Legacy pre-tri-state key; still read for one-way migration into ICON_STYLE.
        val MONOCHROME = booleanPreferencesKey("monochrome_icons")
        val SEARCH_AUTO_LAUNCH = booleanPreferencesKey("search_auto_launch")
        val USAGE_SORT = booleanPreferencesKey("drawer_usage_sort")
        val NOTIFICATION_DOTS = booleanPreferencesKey("notification_dots")
        val SHOW_ALARM = booleanPreferencesKey("show_alarm")
        val SHOW_CALENDAR_EVENT = booleanPreferencesKey("show_calendar_event")
    }
}
