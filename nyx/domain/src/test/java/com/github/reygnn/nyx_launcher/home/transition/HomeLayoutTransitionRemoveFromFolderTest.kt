package com.github.reygnn.nyx_launcher.home.transition

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.FolderEditResult
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.MoveResult
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** REMOVE_FROM_FOLDER_SPEC §2 matrix, one test per row. Pure JVM. */
class HomeLayoutTransitionRemoveFromFolderTest {

    private val grid = GridSpec(columns = 4, rows = 6)

    private fun ck(pkg: String) = ComponentKey(pkg, "$pkg.Main")
    private fun folder(id: String, vararg m: ComponentKey, page: Int, x: Int, y: Int) =
        PlacedItem(HomeItem.Folder(ItemId(id), "", m.toList()), CellPos(page, x, y))
    private fun app(id: String, pkg: String) = HomeItem.App(ItemId(id), ck(pkg))
    private fun layout(items: List<PlacedItem> = emptyList(), dock: List<HomeItem> = emptyList(), pages: Int = 1) =
        HomeLayout(grid, pages, items, dock)

    private fun seq(vararg ids: String): ItemIdFactory {
        val it = ids.iterator(); return ItemIdFactory { ItemId(it.next()) }
    }

    @Test fun extract_from_big_folder_shrinks_it() {
        val f = folder("f", ck("pa"), ck("pb"), ck("pc"), page = 0, x = 0, y = 0)
        val start = layout(items = listOf(f))
        val ids = seq("extracted")
        val r = HomeLayoutTransition.removeFromFolder(start, ItemId("f"), ck("pb"), DropTarget.Cell(CellPos(0, 1, 1)), ids::next)
        assertThat(r).isInstanceOf(FolderEditResult.Extracted::class.java)
        val out = r.layout!!
        val fOut = out.items.first { it.item.id == ItemId("f") }.item as HomeItem.Folder
        assertThat(fOut.members).containsExactly(ck("pa"), ck("pc")).inOrder() // pb removed, order kept
        val extracted = out.items.first { it.pos == CellPos(0, 1, 1) }
        assertThat((extracted.item as HomeItem.App).key).isEqualTo(ck("pb"))
        assertThat(extracted.item.id).isEqualTo(ItemId("extracted"))
    }

    @Test fun extract_from_two_member_folder_dissolves() {
        val f = folder("f", ck("pa"), ck("pb"), page = 0, x = 2, y = 3)
        val start = layout(items = listOf(f))
        val ids = seq("extracted", "survivor")
        val r = HomeLayoutTransition.removeFromFolder(start, ItemId("f"), ck("pa"), DropTarget.Cell(CellPos(0, 0, 0)), ids::next)
        assertThat(r).isInstanceOf(FolderEditResult.FolderDissolved::class.java)
        val out = r.layout!!
        // folder id gone
        assertThat(out.items.any { it.item.id == ItemId("f") }).isFalse()
        // survivor (pb) promoted to the folder's old cell
        val survivor = out.items.first { it.pos == CellPos(0, 2, 3) }
        assertThat((survivor.item as HomeItem.App).key).isEqualTo(ck("pb"))
        assertThat(survivor.item.id).isEqualTo(ItemId("survivor"))
        // extracted (pa) at target
        val extracted = out.items.first { it.pos == CellPos(0, 0, 0) }
        assertThat((extracted.item as HomeItem.App).key).isEqualTo(ck("pa"))
        assertThat(extracted.item.id).isEqualTo(ItemId("extracted"))
    }

    @Test fun dissolve_with_folder_in_dock_puts_survivor_in_dock() {
        val f = HomeItem.Folder(ItemId("f"), "", listOf(ck("pa"), ck("pb")))
        val start = layout(dock = listOf(f))
        val ids = seq("extracted", "survivor")
        val r = HomeLayoutTransition.removeFromFolder(start, ItemId("f"), ck("pa"), DropTarget.Cell(CellPos(0, 0, 0)), ids::next)
        assertThat(r).isInstanceOf(FolderEditResult.FolderDissolved::class.java)
        val out = r.layout!!
        assertThat(out.dock.map { (it as HomeItem.App).key }).containsExactly(ck("pb"))
        assertThat(out.items.first { it.pos == CellPos(0, 0, 0) }.item.id).isEqualTo(ItemId("extracted"))
    }

    @Test fun occupied_target_is_rejected() {
        val f = folder("f", ck("pa"), ck("pb"), ck("pc"), page = 0, x = 0, y = 0)
        val other = PlacedItem(app("o", "po"), CellPos(0, 1, 0))
        val start = layout(items = listOf(f, other))
        val r = HomeLayoutTransition.removeFromFolder(start, ItemId("f"), ck("pb"), DropTarget.Cell(CellPos(0, 1, 0)), seq("x")::next)
        assertThat(r).isEqualTo(FolderEditResult.Rejected(MoveResult.Reason.TARGET_OCCUPIED_INCOMPATIBLE))
    }

    @Test fun off_grid_target_is_rejected() {
        val f = folder("f", ck("pa"), ck("pb"), ck("pc"), page = 0, x = 0, y = 0)
        val start = layout(items = listOf(f))
        val r = HomeLayoutTransition.removeFromFolder(start, ItemId("f"), ck("pb"), DropTarget.Cell(CellPos(0, 9, 9)), seq("x")::next)
        assertThat(r).isEqualTo(FolderEditResult.Rejected(MoveResult.Reason.OFF_GRID))
    }

    @Test fun full_dock_target_is_rejected() {
        val f = folder("f", ck("pa"), ck("pb"), ck("pc"), page = 0, x = 0, y = 0)
        val fullDock = (0 until grid.columns).map { app("d$it", "pd$it") }
        val start = layout(items = listOf(f), dock = fullDock)
        val r = HomeLayoutTransition.removeFromFolder(start, ItemId("f"), ck("pb"), DropTarget.DockSlot(grid.columns), seq("x")::next)
        assertThat(r).isEqualTo(FolderEditResult.Rejected(MoveResult.Reason.DOCK_FULL))
    }

    @Test fun member_not_in_folder_is_noop() {
        val f = folder("f", ck("pa"), ck("pb"), page = 0, x = 0, y = 0)
        val start = layout(items = listOf(f))
        val r = HomeLayoutTransition.removeFromFolder(start, ItemId("f"), ck("zz"), DropTarget.Cell(CellPos(0, 1, 1)), seq("x")::next)
        assertThat(r).isEqualTo(FolderEditResult.NoOp)
    }

    @Test fun non_folder_target_id_is_noop() {
        val a = PlacedItem(app("a", "pa"), CellPos(0, 0, 0))
        val start = layout(items = listOf(a))
        val r = HomeLayoutTransition.removeFromFolder(start, ItemId("a"), ck("pa"), DropTarget.Cell(CellPos(0, 1, 1)), seq("x")::next)
        assertThat(r).isEqualTo(FolderEditResult.NoOp)
    }
}
