package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.LayoutEdit
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.repository.FakeHomeLayoutRepository
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class HomeEditUseCasesTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val grid = GridSpec(columns = 4, rows = 6)
    private fun ck(p: String) = ComponentKey(p, "$p.Main")
    private fun repo(vararg items: PlacedItem) =
        FakeHomeLayoutRepository(HomeLayout(grid, 1, items.toList(), emptyList()))

    @Test
    fun remove_deletes_the_item_and_saves() = runTest(mainDispatcherRule.dispatcher) {
        val r = repo(PlacedItem(HomeItem.App(ItemId("a"), ck("pa")), CellPos(0, 0, 0)))
        val result = RemoveItemUseCase(r, mainDispatcherRule.dispatcher)(ItemId("a"))
        assertThat(result).isInstanceOf(LayoutEdit.Changed::class.java)
        assertThat(r.current.items).isEmpty()
        assertThat(r.saveCount).isEqualTo(1)
    }

    @Test
    fun remove_unknown_does_not_save() = runTest(mainDispatcherRule.dispatcher) {
        val r = repo(PlacedItem(HomeItem.App(ItemId("a"), ck("pa")), CellPos(0, 0, 0)))
        val result = RemoveItemUseCase(r, mainDispatcherRule.dispatcher)(ItemId("ghost"))
        assertThat(result).isEqualTo(LayoutEdit.NoOp)
        assertThat(r.saveCount).isEqualTo(0)
    }

    @Test
    fun rename_sets_title_and_saves() = runTest(mainDispatcherRule.dispatcher) {
        val folder = HomeItem.Folder(ItemId("f"), "", listOf(ck("pa"), ck("pb")))
        val r = repo(PlacedItem(folder, CellPos(0, 0, 0)))
        RenameFolderUseCase(r, mainDispatcherRule.dispatcher)(ItemId("f"), "Games")
        val out = r.current.items.single().item as HomeItem.Folder
        assertThat(out.title).isEqualTo("Games")
        assertThat(r.saveCount).isEqualTo(1)
    }

    @Test
    fun rename_same_title_does_not_save() = runTest(mainDispatcherRule.dispatcher) {
        val folder = HomeItem.Folder(ItemId("f"), "Games", listOf(ck("pa"), ck("pb")))
        val r = repo(PlacedItem(folder, CellPos(0, 0, 0)))
        RenameFolderUseCase(r, mainDispatcherRule.dispatcher)(ItemId("f"), "Games")
        assertThat(r.saveCount).isEqualTo(0)
    }
}
