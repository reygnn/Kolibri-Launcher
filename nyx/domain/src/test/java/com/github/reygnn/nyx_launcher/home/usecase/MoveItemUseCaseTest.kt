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
import com.github.reygnn.nyx_launcher.home.repository.ThrowingHomeLayoutRepository
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

    @Test
    fun folder_creation_persists_and_passes_through_the_folder_id() = runTest(mainDispatcherRule.dispatcher) {
        // B7: FolderCreated is a save-triggering result whose `folder` field must survive the
        // shell. Only Moved was pinned before; the transition matrix is tested elsewhere, so
        // here we only assert the save + result pass-through.
        val a = PlacedItem(HomeItem.App(ItemId("a"), ck("pa")), CellPos(0, 0, 0))
        val b = PlacedItem(HomeItem.App(ItemId("b"), ck("pb")), CellPos(0, 1, 0))
        val repo = FakeHomeLayoutRepository(HomeLayout(grid, 1, listOf(a, b), emptyList()))

        val result = useCase(repo)(ItemId("a"), DropTarget.Cell(CellPos(0, 1, 0))) // a onto b → folder

        assertThat(result).isInstanceOf(MoveResult.FolderCreated::class.java)
        assertThat((result as MoveResult.FolderCreated).folder).isEqualTo(ItemId("folder-new"))
        assertThat(repo.saveCount).isEqualTo(1)
    }

    @Test
    fun add_to_folder_persists_and_passes_through_the_folder_id() = runTest(mainDispatcherRule.dispatcher) {
        // B7: AddedToFolder is the other save-triggering result whose `folder` field runs
        // through the shell.
        val f = PlacedItem(HomeItem.Folder(ItemId("f"), "", listOf(ck("pa"), ck("pb"))), CellPos(0, 0, 0))
        val c = PlacedItem(HomeItem.App(ItemId("c"), ck("pc")), CellPos(0, 1, 0))
        val repo = FakeHomeLayoutRepository(HomeLayout(grid, 1, listOf(f, c), emptyList()))

        val result = useCase(repo)(ItemId("c"), DropTarget.Cell(CellPos(0, 0, 0))) // c onto folder f

        assertThat(result).isInstanceOf(MoveResult.AddedToFolder::class.java)
        assertThat((result as MoveResult.AddedToFolder).folder).isEqualTo(ItemId("f"))
        assertThat(repo.saveCount).isEqualTo(1)
    }

    @Test
    fun a_failed_persist_propagates_out_of_the_use_case() = runTest(mainDispatcherRule.dispatcher) {
        // B1: runLayoutEdit runs the write inside withContext with no runCatching, so a
        // throwing repository surfaces the exception rather than swallowing it and reporting
        // success. Guards against a future runCatching hiding a failed DataStore write.
        val repo = ThrowingHomeLayoutRepository(layoutWithAppAtOrigin())
        val useCase = MoveItemUseCase(repo, ids, mainDispatcherRule.dispatcher)

        val thrown = runCatching {
            useCase(ItemId("a"), DropTarget.Cell(CellPos(0, 1, 1))) // a real Moved → the write fires
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
    }
}
