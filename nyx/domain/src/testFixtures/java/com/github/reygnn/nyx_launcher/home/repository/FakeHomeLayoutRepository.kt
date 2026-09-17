package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [HomeLayoutRepository] test double. Backed by a StateFlow so
 * [layout] re-emits after every write. Exposes [current] and [saveCount] so
 * use-case tests can assert the "save only on change" contract (MIU-INV-3).
 *
 * [update] holds a [Mutex] across read-transform-write, mirroring the impl's
 * atomicity (A1-03) so the shared contract's concurrency case means the same on
 * both sides. [save] takes the same lock; neither re-enters the other.
 */
class FakeHomeLayoutRepository(initial: HomeLayout) : HomeLayoutRepository {

    private val state = MutableStateFlow(initial)
    private val writeMutex = Mutex()

    // First-run seed one-shot, mirroring the impl's SEEDED_KEY: set only by
    // [seedInitialLayout], never by save/update/fit.
    private var seeded = false
    private var seedCounter = 0

    var saveCount = 0
        private set

    val current: HomeLayout get() = state.value

    override fun layout(): Flow<HomeLayout> = state

    override suspend fun save(layout: HomeLayout) = writeMutex.withLock {
        saveCount++
        state.value = layout
    }

    override suspend fun update(transform: suspend (HomeLayout) -> HomeLayout?) =
        writeMutex.withLock {
            transform(state.value)?.let {
                saveCount++
                state.value = it
            }
            Unit
        }

    override suspend fun seedInitialLayout(
        resolveDockApps: suspend () -> List<ComponentKey>,
        resolveGridApps: suspend () -> List<ComponentKey>,
    ): Boolean =
        writeMutex.withLock {
            if (seeded) return@withLock false
            val current = state.value
            if (current.items.isNotEmpty() || current.dock.isNotEmpty()) {
                seeded = true
                return@withLock false
            }
            val dockApps = resolveDockApps()
            val gridApps = resolveGridApps()
            seeded = true
            if (dockApps.isNotEmpty() || gridApps.isNotEmpty()) {
                saveCount++
                state.value = current.copy(
                    items = gridApps.mapIndexed { index, key ->
                        PlacedItem(
                            HomeItem.App(ItemId("seed-${seedCounter++}"), key),
                            CellPos(0, index % current.grid.columns, index / current.grid.columns),
                        )
                    },
                    dock = dockApps.map { HomeItem.App(ItemId("seed-${seedCounter++}"), it) },
                )
            }
            dockApps.isNotEmpty() || gridApps.isNotEmpty()
        }
}
