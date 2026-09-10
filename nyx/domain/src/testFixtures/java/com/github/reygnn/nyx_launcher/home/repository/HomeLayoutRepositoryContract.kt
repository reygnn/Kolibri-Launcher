package com.github.reygnn.nyx_launcher.home.repository

import app.cash.turbine.test
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * The behavioural contract every [HomeLayoutRepository] must satisfy — the
 * abstract half of the triple (CLAUDE.md rule 2). `FakeHomeLayoutRepositoryContractTest`
 * and (in `:data`) `HomeLayoutRepositoryImplContractTest` extend it and only
 * supply [createRepository]; if the fake and the impl drift, one side goes red.
 */
abstract class HomeLayoutRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Provide a fresh repository seeded with [initial]. */
    abstract fun createRepository(initial: HomeLayout): HomeLayoutRepository

    @Test
    fun layout_emits_the_initial_value() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository(WITH_APP)
        repo.layout().test {
            assertThat(awaitItem()).isEqualTo(WITH_APP)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun save_then_layout_emits_the_saved_value() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository(EMPTY)
        repo.layout().test {
            assertThat(awaitItem()).isEqualTo(EMPTY)
            repo.save(WITH_APP)
            assertThat(awaitItem()).isEqualTo(WITH_APP)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        private val GRID = GridSpec(columns = 4, rows = 6)
        val EMPTY = HomeLayout(GRID, pages = 1, items = emptyList(), dock = emptyList())
        val WITH_APP = EMPTY.copy(
            items = listOf(
                PlacedItem(
                    HomeItem.App(ItemId("a"), ComponentKey("pa", "pa.Main")),
                    CellPos(0, 0, 0),
                ),
            ),
        )
    }
}
