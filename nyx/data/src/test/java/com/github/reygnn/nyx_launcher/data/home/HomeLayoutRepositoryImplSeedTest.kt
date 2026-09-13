package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
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
 * First-run seeding ([HomeLayoutRepositoryImpl.seedInitialDock]). This can't live
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

        val seeded = repo.seedInitialDock { listOf(PHONE, SMS) }

        assertThat(seeded).isTrue()
        val dock = repo.layout().first().dock
        assertThat(dock.map { (it as HomeItem.App).key }).containsExactly(PHONE, SMS).inOrder()
    }

    @Test
    fun seeds_only_once() = runTest(mainDispatcherRule.dispatcher) {
        val repo = newRepo()
        assertThat(repo.seedInitialDock { listOf(PHONE) }).isTrue()

        // A returning launch — even one that empties the layout — must not re-seed.
        repo.save(HomeLayout(grid = repo.layout().first().grid, pages = 1, items = emptyList(), dock = emptyList()))
        val second = repo.seedInitialDock { listOf(SMS) }

        assertThat(second).isFalse()
        assertThat(repo.layout().first().dock).isEmpty()
    }

    @Test
    fun preserves_a_grid_already_stamped_by_fit() = runTest(mainDispatcherRule.dispatcher) {
        val repo = newRepo()
        // Simulate FitHomeGridUseCase landing first: an empty layout on the device grid.
        val deviceGrid = GridSpec(columns = 5, rows = 7)
        repo.save(HomeLayout(grid = deviceGrid, pages = 1, items = emptyList(), dock = emptyList()))

        val seeded = repo.seedInitialDock { listOf(PHONE) }

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

        val seeded = repo.seedInitialDock { listOf(PHONE) }

        assertThat(seeded).isFalse()
        assertThat(repo.layout().first().dock.map { (it as HomeItem.App).key }).containsExactly(SMS)
    }

    @Test
    fun does_not_seed_with_no_apps() = runTest(mainDispatcherRule.dispatcher) {
        val repo = newRepo()

        val seeded = repo.seedInitialDock { emptyList() }

        assertThat(seeded).isFalse()
    }

    @Test
    fun does_not_resolve_apps_on_a_returning_install() = runTest(mainDispatcherRule.dispatcher) {
        val repo = newRepo()
        assertThat(repo.seedInitialDock { listOf(PHONE) }).isTrue()

        // Second call: the gate is already set, so the resolver must never run.
        var resolved = false
        val second = repo.seedInitialDock { resolved = true; listOf(SMS) }

        assertThat(second).isFalse()
        assertThat(resolved).isFalse()
    }

    private companion object {
        val PHONE = ComponentKey("com.phone", "com.phone.Main")
        val SMS = ComponentKey("com.sms", "com.sms.Main")
    }
}
