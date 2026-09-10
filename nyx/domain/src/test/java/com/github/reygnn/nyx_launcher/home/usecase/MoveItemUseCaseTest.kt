package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.MoveResult
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.repository.FakeHomeLayoutRepository
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Use-case shell tests. One dispatcher from [MainDispatcherRule], injected into
 * the use-case AND used by `runTest` — no separate `TestScope`/dispatcher
 * (project convention). The transition matrix itself is covered elsewhere; here
 * we only assert read-once / save-on-change / result pass-through.
 */
class MoveItemUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val grid = GridSpec(columns = 4, rows = 6)
    private val ids = ItemIdFactory { ItemId("folder-new") }
    private fun ck(pkg: String) = ComponentKey(pkg, "$pkg.Main")

    private fun layoutWithAppAtOrigin(): HomeLayout = HomeLayout(
        grid,
        pages = 1,
        items = listOf(PlacedItem(HomeItem.App(ItemId("a"), ck("pa")), CellPos(0, 0, 0))),
        dock = emptyList(),
    )

    private fun useCase(repo: FakeHomeLayoutRepository) =
        MoveItemUseCase(repo, ids, mainDispatcherRule.dispatcher)

    @Test
    fun move_persists_the_new_layout_and_returns_moved() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeHomeLayoutRepository(layoutWithAppAtOrigin())
        val result = useCase(repo)(ItemId("a"), DropTarget.Cell(CellPos(0, 1, 1)))

        assertThat(result).isInstanceOf(MoveResult.Moved::class.java)
        assertThat(repo.saveCount).isEqualTo(1)
        assertThat(repo.current.items.single().pos).isEqualTo(CellPos(0, 1, 1))
    }

    @Test
    fun noop_does_not_save() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeHomeLayoutRepository(layoutWithAppAtOrigin())
        val result = useCase(repo)(ItemId("a"), DropTarget.Cell(CellPos(0, 0, 0))) // self-drop

        assertThat(result).isEqualTo(MoveResult.NoOp)
        assertThat(repo.saveCount).isEqualTo(0)
    }

    @Test
    fun rejected_does_not_save() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeHomeLayoutRepository(layoutWithAppAtOrigin())
        val result = useCase(repo)(ItemId("a"), DropTarget.Cell(CellPos(0, 9, 9))) // off-grid

        assertThat(result).isEqualTo(MoveResult.Rejected(MoveResult.Reason.OFF_GRID))
        assertThat(repo.saveCount).isEqualTo(0)
    }
}
