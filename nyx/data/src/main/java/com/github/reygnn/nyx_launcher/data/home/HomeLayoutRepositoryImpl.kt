package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.LayoutSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * DataStore-backed [HomeLayoutRepository]. The whole layout is one JSON blob under
 * [KEY] (rule 5 + rule 22), (de)serialized via the shared [LayoutSerializer].
 *
 * A missing key or an undecodable blob yields [DEFAULT] rather than crashing the
 * read path (DATASTORE_READ_SPEC posture); reconcile + the next save heal it.
 */
class HomeLayoutRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val serializer: LayoutSerializer,
) : HomeLayoutRepository {

    // Serializes all writes: a full [save] and the read-modify-write of [update]
    // both hold it, so concurrent writers can't clobber each other (A1-03).
    private val writeMutex = Mutex()

    override fun layout(): Flow<HomeLayout> = dataStore.data.map { prefs ->
        val raw = prefs[KEY] ?: return@map DEFAULT
        serializer.deserialize(raw) ?: DEFAULT
    }

    override suspend fun save(layout: HomeLayout) = writeMutex.withLock { writeRaw(layout) }

    override suspend fun update(transform: suspend (HomeLayout) -> HomeLayout?) =
        writeMutex.withLock {
            val current = layout().first()
            transform(current)?.let { writeRaw(it) }
            Unit
        }

    private suspend fun writeRaw(layout: HomeLayout) {
        dataStore.edit { it[KEY] = serializer.serialize(layout) }
    }

    private companion object {
        val KEY = stringPreferencesKey("home_layout_v1")

        // Cold-start default only: FitHomeGridUseCase re-fits to the device grid
        // (GridSpecProvider) on start, so these dimensions are just the seed until
        // then (ICON_HOME_MODEL_SPEC §10).
        val DEFAULT = HomeLayout(
            grid = GridSpec(columns = 4, rows = 6),
            pages = 1,
            items = emptyList(),
            dock = emptyList(),
        )
    }
}
