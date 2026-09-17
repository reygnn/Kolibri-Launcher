package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * First-run seeding ([HomeLayoutRepositoryImpl.seedInitialLayout]). This can't live
 * in the shared behavioural contract: seeding is only meaningful over a DataStore
 * that has NEVER been written, and the seeded base grid is an impl-internal
 * default — the fake has no such notion. So it's pinned here, against a fresh
 * [FakeDataStore].
 */
class HomeLayoutRepositoryImplSeedTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Deterministic ids so the assertions don't depend on UUIDs.
    private val ids = object : ItemIdFactory {
        private var n = 0
        override fun next() = ItemId("id-${n++}")
    }

    private fun newRepo() = HomeLayoutRepositoryImpl(FakeDataStore(), HomeLayoutSerializer(), ids)

    @Test
    fun seeds_the_dock_on_a_fresh_store() = runTest(mainDispatcherRule.dispatcher) {
        val repo = newRepo()

        val seeded = repo.seedInitialLayout({ listOf(PHONE, SMS) }, { emptyList() })

        assertThat(seeded).isTrue()
        val dock = repo.layout().first().dock
        assertThat(dock.map { (it as HomeItem.App).key }).containsExactly(PHONE, SMS).inOrder()
    }

    @Test
    fun seeds_grid_apps_top_left_on_a_fresh_store() = runTest(mainDispatcherRule.dispatcher) {
        val repo = newRepo()

        val seeded = repo.seedInitialLayout({ emptyList() }, { listOf(PLAY_STORE) })

        assertThat(seeded).isTrue()
        val items = repo.layout().first().items
        assertThat(items.map { (it.item as HomeItem.App).key }).containsExactly(PLAY_STORE)
        assertThat(items.single().pos).isEqualTo(CellPos(0, 0, 0))
    }

    @Test
    fun seeds_dock_and_grid_together() = runTest(mainDispatcherRule.dispatcher) {
        val repo = newRepo()

        val seeded = repo.seedInitialLayout({ listOf(PHONE) }, { listOf(PLAY_STORE) })

        assertThat(seeded).isTrue()
        val layout = repo.layout().first()
        assertThat(layout.dock.map { (it as HomeItem.App).key }).containsExactly(PHONE)
        assertThat(layout.items.map { (it.item as HomeItem.App).key }).containsExactly(PLAY_STORE)
    }

    @Test
    fun seeds_only_once() = runTest(mainDispatcherRule.dispatcher) {
        val repo = newRepo()
        assertThat(repo.seedInitialLayout({ listOf(PHONE) }, { emptyList() })).isTrue()

        // A returning launch — even one that empties the layout — must not re-seed.
        repo.save(HomeLayout(grid = repo.layout().first().grid, pages = 1, items = emptyList(), dock = emptyList()))
        val second = repo.seedInitialLayout({ listOf(SMS) }, { listOf(PLAY_STORE) })

        assertThat(second).isFalse()
        assertThat(repo.layout().first().dock).isEmpty()
        assertThat(repo.layout().first().items).isEmpty()
    }

    @Test
    fun preserves_a_grid_already_stamped_by_fit() = runTest(mainDispatcherRule.dispatcher) {
        val repo = newRepo()
        // Simulate FitHomeGridUseCase landing first: an empty layout on the device grid.
        val deviceGrid = GridSpec(columns = 5, rows = 7)
        repo.save(HomeLayout(grid = deviceGrid, pages = 1, items = emptyList(), dock = emptyList()))

        val seeded = repo.seedInitialLayout({ listOf(PHONE) }, { emptyList() })

        assertThat(seeded).isTrue()
        val layout = repo.layout().first()
        assertThat(layout.grid).isEqualTo(deviceGrid)
        assertThat(layout.dock.map { (it as HomeItem.App).key }).containsExactly(PHONE)
    }

    @Test
    fun does_not_seed_when_content_already_exists() = runTest(mainDispatcherRule.dispatcher) {
        val repo = newRepo()
        repo.save(
            HomeLayout(
                grid = GridSpec(columns = 4, rows = 6),
                pages = 1,
                items = emptyList(),
                dock = listOf(HomeItem.App(ItemId("existing"), SMS)),
            ),
        )

        val seeded = repo.seedInitialLayout({ listOf(PHONE) }, { listOf(PLAY_STORE) })

        assertThat(seeded).isFalse()
        assertThat(repo.layout().first().dock.map { (it as HomeItem.App).key }).containsExactly(SMS)
        assertThat(repo.layout().first().items).isEmpty()
    }

    @Test
    fun does_not_seed_with_no_apps() = runTest(mainDispatcherRule.dispatcher) {
        val repo = newRepo()

        val seeded = repo.seedInitialLayout({ emptyList() }, { emptyList() })

        assertThat(seeded).isFalse()
    }

    @Test
    fun does_not_resolve_apps_on_a_returning_install() = runTest(mainDispatcherRule.dispatcher) {
        val repo = newRepo()
        assertThat(repo.seedInitialLayout({ listOf(PHONE) }, { emptyList() })).isTrue()

        // Second call: the gate is already set, so neither resolver must run.
        var resolvedDock = false
        var resolvedGrid = false
        val second = repo.seedInitialLayout(
            { resolvedDock = true; listOf(SMS) },
            { resolvedGrid = true; listOf(PLAY_STORE) },
        )

        assertThat(second).isFalse()
        assertThat(resolvedDock).isFalse()
        assertThat(resolvedGrid).isFalse()
    }

    @Test
    fun update_propagates_read_failure_and_does_not_write() = runTest(mainDispatcherRule.dispatcher) {
        // The RMW read is fail-CLOSED: an IOException propagates and the write never runs, so a
        // transient store failure can't clobber the real layout with DEFAULT. (A fail-open revert
        // would read DEFAULT, not throw, and proceed to write.)
        var wrote = false
        val throwing = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("boom") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                wrote = true
                return transform(emptyPreferences())
            }
        }
        val repo = HomeLayoutRepositoryImpl(throwing, HomeLayoutSerializer(), ids)

        var thrown = false
        try {
            repo.update { it.copy(pages = it.pages + 1) }
        } catch (e: IOException) {
            thrown = true
        }

        assertThat(thrown).isTrue()
        assertThat(wrote).isFalse()
    }

    @Test
    fun seed_is_skipped_when_the_store_read_throws() = runTest(mainDispatcherRule.dispatcher) {
        // Contained fail-closed: an IOException on the seed read is caught → no seed, no crash.
        val throwing = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("boom") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                emptyPreferences()
        }
        val repo = HomeLayoutRepositoryImpl(throwing, HomeLayoutSerializer(), ids)

        val seeded = repo.seedInitialLayout({ listOf(PHONE) }, { listOf(SMS) })

        assertThat(seeded).isFalse()
    }

    private companion object {
        val PHONE = ComponentKey("com.phone", "com.phone.Main")
        val SMS = ComponentKey("com.sms", "com.sms.Main")
        val PLAY_STORE = ComponentKey("com.android.vending", "com.android.vending.Main")
    }
}
