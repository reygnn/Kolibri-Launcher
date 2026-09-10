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

    private companion object {
        val MONOCHROME = booleanPreferencesKey("monochrome_icons")
    }
}
