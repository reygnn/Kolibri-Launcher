package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.nyx_launcher.home.model.DrawerFolders
import com.github.reygnn.nyx_launcher.home.repository.DrawerFoldersRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * DataStore-backed [DrawerFoldersRepository]. Membership is one JSON blob under
 * [KEY], (de)serialized via [DrawerFoldersSerializer]. Shares Nyx's single
 * `DataStore<Preferences>` with the other repositories but under its OWN key, so the
 * drawer-folder state is independent of the home layout (DRAWER_FOLDERS_SPEC §4 / D-5).
 *
 * A missing key or an undecodable blob reads as [DrawerFolders.EMPTY] rather than
 * crashing the read path; the next [update] write heals it.
 */
class DrawerFoldersRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val serializer: DrawerFoldersSerializer,
) : DrawerFoldersRepository {

    // Serializes the read-modify-write of [update] so concurrent writers can't
    // clobber each other on a stale read (mirrors HomeLayoutRepositoryImpl, A1-03).
    private val writeMutex = Mutex()

    override fun folders(): Flow<DrawerFolders> = dataStore.data.map { prefs ->
        val raw = prefs[KEY] ?: return@map DrawerFolders.EMPTY
        serializer.deserialize(raw) ?: DrawerFolders.EMPTY
    }

    override suspend fun update(transform: suspend (DrawerFolders) -> DrawerFolders?) =
        writeMutex.withLock {
            val current = folders().first()
            transform(current)?.let { writeRaw(it) }
            Unit
        }

    private suspend fun writeRaw(folders: DrawerFolders) {
        dataStore.edit { it[KEY] = serializer.serialize(folders) }
    }

    private companion object {
        val KEY = stringPreferencesKey("drawer_folders_v1")
    }
}
