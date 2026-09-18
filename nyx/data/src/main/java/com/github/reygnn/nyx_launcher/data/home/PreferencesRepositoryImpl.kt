package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.github.reygnn.launcher.common.data.readFlowFailOpen
import com.github.reygnn.launcher.core.AppConstants
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

    override fun monochromeIcons(): Flow<Boolean> =
        dataStore.readFlowFailOpen("Error reading monochromeIcons") { it[MONOCHROME] ?: false }

    override suspend fun setMonochromeIcons(enabled: Boolean) {
        dataStore.edit { it[MONOCHROME] = enabled }
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

    override fun rotationLocked(): Flow<Boolean> =
        dataStore.readFlowFailOpen("Error reading rotationLocked") {
            it[ROTATION_LOCKED] ?: AppConstants.DEFAULT_ROTATION_LOCKED
        }

    override suspend fun setRotationLocked(enabled: Boolean) {
        dataStore.edit { it[ROTATION_LOCKED] = enabled }
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

    private companion object {
        val MONOCHROME = booleanPreferencesKey("monochrome_icons")
        val SEARCH_AUTO_LAUNCH = booleanPreferencesKey("search_auto_launch")
        val USAGE_SORT = booleanPreferencesKey("drawer_usage_sort")
        val ROTATION_LOCKED = booleanPreferencesKey("rotation_locked")
        val SHOW_ALARM = booleanPreferencesKey("show_alarm")
        val SHOW_CALENDAR_EVENT = booleanPreferencesKey("show_calendar_event")
    }
}
