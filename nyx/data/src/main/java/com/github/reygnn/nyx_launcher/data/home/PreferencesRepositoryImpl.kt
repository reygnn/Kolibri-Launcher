package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** DataStore-backed [PreferencesRepository] (shares the app's Preferences store). */
class PreferencesRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : PreferencesRepository {

    override fun monochromeIcons(): Flow<Boolean> =
        dataStore.data.map { it[MONOCHROME] ?: false }

    override suspend fun setMonochromeIcons(enabled: Boolean) {
        dataStore.edit { it[MONOCHROME] = enabled }
    }

    override fun searchAutoLaunch(): Flow<Boolean> =
        dataStore.data.map { it[SEARCH_AUTO_LAUNCH] ?: false }

    override suspend fun setSearchAutoLaunch(enabled: Boolean) {
        dataStore.edit { it[SEARCH_AUTO_LAUNCH] = enabled }
    }

    override val showAlarmFlow: Flow<Boolean> =
        dataStore.data.map { it[SHOW_ALARM] ?: false }

    override suspend fun setShowAlarm(enabled: Boolean) {
        dataStore.edit { it[SHOW_ALARM] = enabled }
    }

    override val showCalendarEventFlow: Flow<Boolean> =
        dataStore.data.map { it[SHOW_CALENDAR_EVENT] ?: false }

    override suspend fun setShowCalendarEvent(enabled: Boolean) {
        dataStore.edit { it[SHOW_CALENDAR_EVENT] = enabled }
    }

    private companion object {
        val MONOCHROME = booleanPreferencesKey("monochrome_icons")
        val SEARCH_AUTO_LAUNCH = booleanPreferencesKey("search_auto_launch")
        val SHOW_ALARM = booleanPreferencesKey("show_alarm")
        val SHOW_CALENDAR_EVENT = booleanPreferencesKey("show_calendar_event")
    }
}
