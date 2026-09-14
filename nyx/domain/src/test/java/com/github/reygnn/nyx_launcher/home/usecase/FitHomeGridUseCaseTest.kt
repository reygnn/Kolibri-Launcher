package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.repository.FakeHomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.ThrowingHomeLayoutRepository
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * [FitHomeGridUseCase] shell test (A1-12). The regrid matrix lives in
 * `HomeLayoutRegridderTest`; here we only pin the save-gating that runs on every
 * MainActivity layout pass — an already-matching grid must NOT write (dropping
 * that guard would cause a persist storm on every layout/orientation event).
 */
class FitHomeGridUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val grid = GridSpec(columns = 4, rows = 6)

    private fun layout(spec: GridSpec): HomeLayout = HomeLayout(
        spec,
        pages = 1,
        items = listOf(PlacedItem(HomeItem.App(ItemId("a"), ComponentKey("pa", "pa.Main")), CellPos(0, 0, 0))),
        dock = emptyList(),
    )

    private fun useCase(repo: FakeHomeLayoutRepository) =
        FitHomeGridUseCase(repo, mainDispatcherRule.dispatcher)

    @Test
    fun already_matching_grid_does_not_save() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeHomeLayoutRepository(layout(grid))

        useCase(repo)(grid) // target == current grid

        assertThat(repo.saveCount).isEqualTo(0)
        assertThat(repo.current.grid).isEqualTo(grid)
    }

    @Test
    fun differing_grid_saves_the_regridded_layout_once() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeHomeLayoutRepository(layout(grid))
        val target = GridSpec(columns = 5, rows = 8)

        useCase(repo)(target)

        assertThat(repo.saveCount).isEqualTo(1)
        assertThat(repo.current.grid).isEqualTo(target)
        // The single item survives the regrid (regridder repacks, never drops).
        assertThat(repo.current.items).hasSize(1)
    }

    @Test
    fun a_failed_persist_propagates_out_of_the_use_case() = runTest(mainDispatcherRule.dispatcher) {
        // B1: FitHomeGridUseCase writes via repository.update inside withContext with no
        // runCatching — a throwing repository must surface, not be swallowed and reported as
        // a successful (silent) layout pass.
        val repo = ThrowingHomeLayoutRepository(layout(grid))
        val useCase = FitHomeGridUseCase(repo, mainDispatcherRule.dispatcher)
        val target = GridSpec(columns = 5, rows = 8) // a real regrid → the write path fires

        val thrown = runCatching { useCase(target) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
    }
}
