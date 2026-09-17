package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
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
    private val itemIdFactory: ItemIdFactory,
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

    override suspend fun seedInitialLayout(
        resolveDockApps: suspend () -> List<ComponentKey>,
        resolveGridApps: suspend () -> List<ComponentKey>,
    ): Boolean =
        writeMutex.withLock {
            val prefs = dataStore.data.first()
            // One-shot: [SEEDED_KEY] records that the first-run decision was already
            // made. Gated FIRST, before resolving apps, so a returning install never
            // runs the resolver's system IPCs again. A plain KEY-presence gate can't be
            // used because FitHomeGridUseCase writes an empty layout under KEY on the
            // first layout pass. This flag is never touched by save/update/fit.
            if (prefs[SEEDED_KEY] == true) return@withLock false
            // Seed onto whatever is already there so a grid already stamped by fit is
            // preserved (never reset to DEFAULT's grid). If content already exists —
            // e.g. an import landed first — the layout is established: mark the decision
            // done and leave it untouched (still without resolving apps).
            val current = prefs[KEY]?.let { serializer.deserialize(it) } ?: DEFAULT
            if (current.items.isNotEmpty() || current.dock.isNotEmpty()) {
                dataStore.edit { it[SEEDED_KEY] = true }
                return@withLock false
            }
            // Resolve only now that we know we will seed. No cap here: the seed-time
            // grid is DEFAULT (before FitHomeGridUseCase stamps the real device grid),
            // so capping on it would wrongly drop apps on a wider device. Dock capacity
            // is enforced later by the regridder against the REAL device grid, which
            // re-homes any overflow onto the grid (never drops it).
            val dockApps = resolveDockApps()
            val gridApps = resolveGridApps()
            dataStore.edit {
                it[SEEDED_KEY] = true
                if (dockApps.isNotEmpty() || gridApps.isNotEmpty()) {
                    val dock = dockApps.map { key -> HomeItem.App(itemIdFactory.next(), key) }
                    val items = placeOnGrid(gridApps, current.grid.columns)
                    it[KEY] = serializer.serialize(current.copy(items = items, dock = dock))
                }
            }
            dockApps.isNotEmpty() || gridApps.isNotEmpty()
        }

    // Lay grid-seed apps out row-major from the top-left of page 0. The seed grid is
    // usually DEFAULT (4×6) — FitHomeGridUseCase re-fits to the real device grid on
    // start — so (0,0) is always valid and a small seed never overflows.
    private fun placeOnGrid(apps: List<ComponentKey>, columns: Int): List<PlacedItem> =
        apps.mapIndexed { index, key ->
            PlacedItem(
                HomeItem.App(itemIdFactory.next(), key),
                CellPos(page = 0, x = index % columns, y = index / columns),
            )
        }

    private suspend fun writeRaw(layout: HomeLayout) {
        dataStore.edit { it[KEY] = serializer.serialize(layout) }
    }

    private companion object {
        val KEY = stringPreferencesKey("home_layout_v1")

        // First-run seed one-shot (see seedInitialLayout). Separate from KEY so a
        // fit-only write doesn't read as "already seeded".
        val SEEDED_KEY = booleanPreferencesKey("home_dock_seeded_v1")

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
