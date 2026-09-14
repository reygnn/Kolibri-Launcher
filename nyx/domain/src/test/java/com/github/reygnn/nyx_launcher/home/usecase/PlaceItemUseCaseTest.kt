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
    fun placing_an_app_that_is_only_a_folder_member_adds_a_fresh_tile() = runTest(mainDispatcherRule.dispatcher) {
        // Scoped IHM-INV-7: folder membership is its OWN scope, so a drawer place of an app
        // that currently lives only in a folder creates a fresh grid tile — the app becomes a
        // tile AND stays a member (cross-scope coexistence). This is the case the old
        // `isFolderMember → NoOp` guard forbade; it is now a real placement that persists.
        val folder = HomeItem.Folder(ItemId("f"), "", listOf(ck("pa"), ck("pb")))
        val start = empty().copy(items = listOf(PlacedItem(folder, CellPos(0, 0, 0))))
        val repo = FakeHomeLayoutRepository(start)
        val result = PlaceItemUseCase(repo, ids, mainDispatcherRule.dispatcher)(ck("pa"), DropTarget.Cell(CellPos(0, 2, 2)))
        assertThat(result).isInstanceOf(MoveResult.Moved::class.java)
        assertThat(repo.saveCount).isEqualTo(1)
        // folder untouched — pa still a member (independent scope)
        val f = repo.current.items.first { it.item.id == ItemId("f") }.item as HomeItem.Folder
        assertThat(f.members).containsExactly(ck("pa"), ck("pb")).inOrder()
        // fresh tile created for pa at the target
        val tile = repo.current.items.first { it.pos == CellPos(0, 2, 2) }
        assertThat((tile.item as HomeItem.App).key).isEqualTo(ck("pa"))
        assertThat(tile.item.id).isEqualTo(ItemId("new"))
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
