package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.launcher.core.ComponentKey
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

class PlaceItemUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val grid = GridSpec(columns = 4, rows = 6)
    private val ids = ItemIdFactory { ItemId("new") }
    private fun ck(p: String) = ComponentKey(p, "$p.Main")
    private fun empty() = HomeLayout(grid, 1, emptyList(), emptyList())

    @Test
    fun places_a_new_app_and_saves() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeHomeLayoutRepository(empty())
        val result = PlaceItemUseCase(repo, ids, mainDispatcherRule.dispatcher)(
            ck("pa"), DropTarget.Cell(CellPos(0, 1, 1)),
        )
        assertThat(result).isInstanceOf(MoveResult.Moved::class.java)
        assertThat(repo.saveCount).isEqualTo(1)
        assertThat(repo.current.items.single().pos).isEqualTo(CellPos(0, 1, 1))
    }

    @Test
    fun placing_an_already_placed_app_moves_it_without_duplicating() = runTest(mainDispatcherRule.dispatcher) {
        val start = empty().copy(
            items = listOf(PlacedItem(HomeItem.App(ItemId("a"), ck("pa")), CellPos(0, 0, 0))),
        )
        val repo = FakeHomeLayoutRepository(start)
        PlaceItemUseCase(repo, ids, mainDispatcherRule.dispatcher)(ck("pa"), DropTarget.Cell(CellPos(0, 2, 2)))

        assertThat(repo.current.items).hasSize(1) // HEU-INV-1: no duplicate
        assertThat(repo.current.items.single().pos).isEqualTo(CellPos(0, 2, 2))
    }

    @Test
    fun placing_an_app_that_is_already_a_folder_member_is_a_noop_and_does_not_save() = runTest(mainDispatcherRule.dispatcher) {
        // place()'s folder-member guard (HomeLayoutTransition: isFolderMember → NoOp) is
        // unique to the place path (no analogue in move). It must NOT re-add the app (HEU-INV-1
        // uniqueness) and must NOT persist — the persist-storm guard for place, untested until now.
        val folder = HomeItem.Folder(ItemId("f"), "", listOf(ck("pa"), ck("pb")))
        val start = empty().copy(items = listOf(PlacedItem(folder, CellPos(0, 0, 0))))
        val repo = FakeHomeLayoutRepository(start)
        val result = PlaceItemUseCase(repo, ids, mainDispatcherRule.dispatcher)(ck("pa"), DropTarget.Cell(CellPos(0, 2, 2)))
        assertThat(result).isEqualTo(MoveResult.NoOp)
        assertThat(repo.saveCount).isEqualTo(0)
        assertThat(repo.current).isEqualTo(start) // layout untouched
    }

    @Test
    fun placing_onto_an_off_grid_cell_is_rejected_and_does_not_save() = runTest(mainDispatcherRule.dispatcher) {
        // A Rejected outcome (FolderEditResult/MoveResult.Rejected carries layout == null)
        // must surface as Rejected and leave the repository untouched.
        val repo = FakeHomeLayoutRepository(empty())
        val result = PlaceItemUseCase(repo, ids, mainDispatcherRule.dispatcher)(ck("pa"), DropTarget.Cell(CellPos(0, 9, 9)))
        assertThat(result).isEqualTo(MoveResult.Rejected(MoveResult.Reason.OFF_GRID))
        assertThat(repo.saveCount).isEqualTo(0)
    }

    @Test
    fun re_placing_an_already_placed_app_on_its_own_cell_is_a_noop_and_does_not_save() = runTest(mainDispatcherRule.dispatcher) {
        // place() of an already-placed app redirects to move(); dropping it on its OWN cell
        // is a NoOp that must not persist — pins the redirect's save-gating (a redirect that
        // always re-emitted the layout would persist-storm on every re-place).
        val start = empty().copy(items = listOf(PlacedItem(HomeItem.App(ItemId("a"), ck("pa")), CellPos(0, 1, 1))))
        val repo = FakeHomeLayoutRepository(start)
        val result = PlaceItemUseCase(repo, ids, mainDispatcherRule.dispatcher)(ck("pa"), DropTarget.Cell(CellPos(0, 1, 1)))
        assertThat(result).isEqualTo(MoveResult.NoOp)
        assertThat(repo.saveCount).isEqualTo(0)
    }
}
