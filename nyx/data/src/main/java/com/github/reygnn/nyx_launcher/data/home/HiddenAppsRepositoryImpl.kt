package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.repository.HiddenAppsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * DataStore-backed [HiddenAppsRepository]. The hidden set is one JSON blob under [KEY],
 * (de)serialized via [HiddenAppsSerializer]. Shares Nyx's single `DataStore<Preferences>`
 * with the other repositories but under its OWN key, so the hidden state is independent of
 * the home layout and drawer folders.
 *
 * A missing key or an undecodable blob reads as the empty set rather than crashing the read
 * path; the next [update] write heals it. Mirrors [DrawerFoldersRepositoryImpl].
 */
class HiddenAppsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val serializer: HiddenAppsSerializer,
) : HiddenAppsRepository {

    // Serializes the read-modify-write of [update] so concurrent writers can't clobber each
    // other on a stale read (mirrors HomeLayoutRepositoryImpl, A1-03).
    private val writeMutex = Mutex()

    override fun hidden(): Flow<Set<ComponentKey>> = dataStore.data.map { prefs ->
        val raw = prefs[KEY] ?: return@map emptySet()
        serializer.deserialize(raw) ?: emptySet()
    }

    override suspend fun update(transform: suspend (Set<ComponentKey>) -> Set<ComponentKey>?) =
        writeMutex.withLock {
            val current = hidden().first()
            transform(current)?.let { writeRaw(it) }
            Unit
        }

    private suspend fun writeRaw(hidden: Set<ComponentKey>) {
        dataStore.edit { it[KEY] = serializer.serialize(hidden) }
    }

    private companion object {
        val KEY = stringPreferencesKey("hidden_apps_v1")
    }
}
