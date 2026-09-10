package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.FolderEditResult
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.repository.FakeHomeLayoutRepository
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class RemoveFromFolderUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val grid = GridSpec(columns = 4, rows = 6)
    private val ids = ItemIdFactory { ItemId("extracted") }
    private fun ck(p: String) = ComponentKey(p, "$p.Main")

    @Test
    fun extract_persists_and_returns_extracted() = runTest(mainDispatcherRule.dispatcher) {
        val folder = HomeItem.Folder(ItemId("f"), "", listOf(ck("pa"), ck("pb"), ck("pc")))
        val start = HomeLayout(grid, 1, listOf(PlacedItem(folder, CellPos(0, 0, 0))), emptyList())
        val repo = FakeHomeLayoutRepository(start)

        val result = RemoveFromFolderUseCase(repo, ids, mainDispatcherRule.dispatcher)(
            ItemId("f"), ck("pb"), DropTarget.Cell(CellPos(0, 1, 1)),
        )

        assertThat(result).isInstanceOf(FolderEditResult.Extracted::class.java)
        assertThat(repo.saveCount).isEqualTo(1)
        val extracted = repo.current.items.first { it.pos == CellPos(0, 1, 1) }
        assertThat((extracted.item as HomeItem.App).key).isEqualTo(ck("pb"))
    }

    @Test
    fun member_not_in_folder_does_not_save() = runTest(mainDispatcherRule.dispatcher) {
        val folder = HomeItem.Folder(ItemId("f"), "", listOf(ck("pa"), ck("pb")))
        val start = HomeLayout(grid, 1, listOf(PlacedItem(folder, CellPos(0, 0, 0))), emptyList())
        val repo = FakeHomeLayoutRepository(start)

        val result = RemoveFromFolderUseCase(repo, ids, mainDispatcherRule.dispatcher)(
            ItemId("f"), ck("zz"), DropTarget.Cell(CellPos(0, 1, 1)),
        )

        assertThat(result).isEqualTo(FolderEditResult.NoOp)
        assertThat(repo.saveCount).isEqualTo(0)
    }
}
