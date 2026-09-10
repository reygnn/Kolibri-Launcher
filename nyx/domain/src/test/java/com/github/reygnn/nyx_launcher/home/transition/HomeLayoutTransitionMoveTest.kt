package com.github.reygnn.nyx_launcher.home.transition

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.MoveResult
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The MOVE_ITEM_SPEC §3 drop-matrix, one test per row. Pure JVM, no dispatcher,
 * no mocks — the transition is a total function (MIU-INV-1/-2).
 */
class HomeLayoutTransitionMoveTest {

    private val grid = GridSpec(columns = 4, rows = 6)
    private val newId = com.github.reygnn.nyx_launcher.home.model.ItemIdFactory { ItemId("folder-new") }

    private fun ck(pkg: String) = ComponentKey(pkg, "$pkg.Main")
    private fun app(id: String, pkg: String = id) = HomeItem.App(ItemId(id), ck(pkg))
    private fun folder(id: String, vararg members: ComponentKey) =
        HomeItem.Folder(ItemId(id), title = "", members = members.toList())
    private fun placed(item: HomeItem, page: Int, x: Int, y: Int) =
        PlacedItem(item, CellPos(page, x, y))
    private fun layout(
        items: List<PlacedItem> = emptyList(),
        dock: List<HomeItem> = emptyList(),
        pages: Int = 1,
    ) = HomeLayout(grid, pages, items, dock)

    private fun move(l: HomeLayout, moving: ItemId, target: DropTarget) =
        HomeLayoutTransition.move(l, moving, target, newId::next)

    // ---- Cell targets (§3.1) ----

    @Test fun app_to_empty_cell_moves() {
        val a = app("a")
        val start = layout(items = listOf(placed(a, 0, 0, 0)))
        val r = move(start, a.id, DropTarget.Cell(CellPos(0, 1, 1)))
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.items).hasSize(1)
        assertThat(out.items.single().pos).isEqualTo(CellPos(0, 1, 1))
        assertThat(out.items.single().item.id).isEqualTo(a.id)
    }

    @Test fun app_to_own_cell_is_noop() {
        val a = app("a")
        val start = layout(items = listOf(placed(a, 0, 0, 0)))
        val r = move(start, a.id, DropTarget.Cell(CellPos(0, 0, 0)))
        assertThat(r).isEqualTo(MoveResult.NoOp)
        assertThat(r.layout).isNull()
    }

    @Test fun app_onto_app_creates_folder_target_first() {
        val a = app("a", "pa")
        val b = app("b", "pb")
        val start = layout(items = listOf(placed(a, 0, 0, 0), placed(b, 0, 1, 0)))
        val r = move(start, a.id, DropTarget.Cell(CellPos(0, 1, 0))) // drop a onto b
        assertThat(r).isInstanceOf(MoveResult.FolderCreated::class.java)
        val fc = r as MoveResult.FolderCreated
        assertThat(fc.folder).isEqualTo(ItemId("folder-new"))
        val out = fc.layout!!
        assertThat(out.items).hasSize(1)
        val placedFolder = out.items.single()
        assertThat(placedFolder.pos).isEqualTo(CellPos(0, 1, 0))
        val f = placedFolder.item as HomeItem.Folder
        assertThat(f.members).containsExactly(ck("pb"), ck("pa")).inOrder() // target, then dragged
        assertThat(f.title).isEmpty()
    }

    @Test fun app_onto_folder_appends_member() {
        val f = folder("f", ck("pb"), ck("pc"))
        val a = app("a", "pa")
        val start = layout(items = listOf(placed(a, 0, 0, 0), placed(f, 0, 1, 0)))
        val r = move(start, a.id, DropTarget.Cell(CellPos(0, 1, 0)))
        assertThat(r).isInstanceOf(MoveResult.AddedToFolder::class.java)
        val out = r.layout!!
        val fOut = out.items.first { it.item.id == f.id }.item as HomeItem.Folder
        assertThat(fOut.members).containsExactly(ck("pb"), ck("pc"), ck("pa")).inOrder()
        assertThat(out.items.any { it.item.id == a.id }).isFalse()
    }

    @Test fun folder_to_empty_cell_moves() {
        val f = folder("f", ck("pa"), ck("pb"))
        val start = layout(items = listOf(placed(f, 0, 0, 0)))
        val r = move(start, f.id, DropTarget.Cell(CellPos(0, 2, 2)))
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        assertThat(r.layout!!.items.single().pos).isEqualTo(CellPos(0, 2, 2))
    }

    @Test fun folder_onto_app_is_rejected() {
        val f = folder("f", ck("pa"), ck("pb"))
        val b = app("b", "pc")
        val start = layout(items = listOf(placed(f, 0, 0, 0), placed(b, 0, 1, 0)))
        val r = move(start, f.id, DropTarget.Cell(CellPos(0, 1, 0)))
        assertThat(r).isEqualTo(MoveResult.Rejected(MoveResult.Reason.TARGET_OCCUPIED_INCOMPATIBLE))
    }

    @Test fun folder_onto_folder_is_rejected() {
        val f1 = folder("f1", ck("pa"), ck("pb"))
        val f2 = folder("f2", ck("pc"), ck("pd"))
        val start = layout(items = listOf(placed(f1, 0, 0, 0), placed(f2, 0, 1, 0)))
        val r = move(start, f1.id, DropTarget.Cell(CellPos(0, 1, 0)))
        assertThat(r).isEqualTo(MoveResult.Rejected(MoveResult.Reason.TARGET_OCCUPIED_INCOMPATIBLE))
    }

    @Test fun drop_on_new_trailing_page_appends_a_page() {
        val a = app("a")
        val start = layout(items = listOf(placed(a, 0, 0, 0)), pages = 1)
        val r = move(start, a.id, DropTarget.Cell(CellPos(1, 0, 0))) // page == pages
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.pages).isEqualTo(2)
        assertThat(out.items.single().pos).isEqualTo(CellPos(1, 0, 0))
    }

    @Test fun off_grid_targets_are_rejected() {
        val a = app("a")
        val start = layout(items = listOf(placed(a, 0, 0, 0)))
        assertThat(move(start, a.id, DropTarget.Cell(CellPos(0, 9, 9))))
            .isEqualTo(MoveResult.Rejected(MoveResult.Reason.OFF_GRID))
        assertThat(move(start, a.id, DropTarget.Cell(CellPos(5, 0, 0)))) // page > pages
            .isEqualTo(MoveResult.Rejected(MoveResult.Reason.OFF_GRID))
    }

    // ---- Dock targets (§3.2) ----

    @Test fun app_to_empty_dock_slot_moves_in() {
        val a = app("a")
        val start = layout(items = listOf(placed(a, 0, 0, 0)))
        val r = move(start, a.id, DropTarget.DockSlot(0))
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.dock.map { it.id }).containsExactly(a.id)
        assertThat(out.items).isEmpty()
    }

    @Test fun full_dock_is_rejected() {
        val a = app("a")
        val fullDock = (0 until grid.columns).map { app("d$it", "pd$it") }
        val start = layout(items = listOf(placed(a, 0, 0, 0)), dock = fullDock)
        val r = move(start, a.id, DropTarget.DockSlot(grid.columns))
        assertThat(r).isEqualTo(MoveResult.Rejected(MoveResult.Reason.DOCK_FULL))
    }

    @Test fun occupied_dock_slot_is_rejected() {
        val a = app("a")
        val x = app("x", "px")
        val start = layout(items = listOf(placed(a, 0, 0, 0)), dock = listOf(x))
        val r = move(start, a.id, DropTarget.DockSlot(0)) // slot 0 is occupied by x
        assertThat(r).isEqualTo(MoveResult.Rejected(MoveResult.Reason.TARGET_OCCUPIED_INCOMPATIBLE))
    }

    @Test fun dock_item_to_own_slot_is_noop() {
        val a = app("a")
        val start = layout(dock = listOf(a))
        val r = move(start, a.id, DropTarget.DockSlot(0))
        assertThat(r).isEqualTo(MoveResult.NoOp)
    }

    @Test fun dock_item_to_grid_moves_out_of_dock() {
        val a = app("a")
        val start = layout(dock = listOf(a))
        val r = move(start, a.id, DropTarget.Cell(CellPos(0, 0, 0)))
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.dock).isEmpty()
        assertThat(out.items.single().item.id).isEqualTo(a.id)
    }

    // ---- Programmer-error precondition (§MIU-INV-2) ----

    @Test fun unknown_moving_id_is_noop() {
        val a = app("a")
        val start = layout(items = listOf(placed(a, 0, 0, 0)))
        val r = move(start, ItemId("ghost"), DropTarget.Cell(CellPos(0, 1, 1)))
        assertThat(r).isEqualTo(MoveResult.NoOp)
    }
}
