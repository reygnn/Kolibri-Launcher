package com.github.reygnn.nyx_launcher.home.repository

import app.cash.turbine.test
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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

    @Test
    fun update_persists_the_transformed_layout() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository(EMPTY)
        repo.layout().test {
            assertThat(awaitItem()).isEqualTo(EMPTY)
            repo.update { WITH_APP }
            assertThat(awaitItem()).isEqualTo(WITH_APP)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun update_returning_null_does_not_write() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository(WITH_APP)
        repo.layout().test {
            assertThat(awaitItem()).isEqualTo(WITH_APP)
            repo.update { null }
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun update_receives_the_current_layout() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository(WITH_APP)
        var seen: HomeLayout? = null
        repo.update { current ->
            seen = current
            current.copy(pages = current.pages + 1)
        }
        assertThat(seen).isEqualTo(WITH_APP)
        repo.layout().test {
            assertThat(awaitItem()).isEqualTo(WITH_APP.copy(pages = WITH_APP.pages + 1))
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Two concurrent [HomeLayoutRepository.update] calls, each doing a
     * read-transform-write of `pages`, must not lose a write (A1-03). The
     * `yield()` inside each transform forces the coroutines to interleave: with a
     * non-atomic read-modify-write both would read the same start value and the
     * final result would be `start + 1`. Serializing the whole transform under a
     * lock is what makes the result `start + 2`. Goes red on any repository whose
     * `update` does not hold across the transform.
     */
    @Test
    fun concurrent_updates_do_not_lose_writes() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository(EMPTY)
        val increment: suspend (HomeLayout) -> HomeLayout = { current ->
            yield()
            current.copy(pages = current.pages + 1)
        }
        val a = launch { repo.update(increment) }
        val b = launch { repo.update(increment) }
        a.join()
        b.join()
        assertThat(repo.layout().first().pages).isEqualTo(EMPTY.pages + 2)
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
