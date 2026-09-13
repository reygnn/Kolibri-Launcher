package com.github.reygnn.nyx_launcher.home.transition

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.launcher.core.ComponentKey
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

    @Test fun drop_before_a_dock_icon_inserts_there() {
        val a = app("a")
        val x = app("x", "px")
        val start = layout(items = listOf(placed(a, 0, 0, 0)), dock = listOf(x))
        val r = move(start, a.id, DropTarget.DockSlot(0)) // insert before x, not a folder
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.dock.map { it.id }).containsExactly(a.id, x.id).inOrder()
        assertThat(out.items).isEmpty()
    }

    @Test fun drop_between_two_dock_icons_inserts_between() {
        val a = app("a")
        val x = app("x", "px")
        val y = app("y", "py")
        val start = layout(items = listOf(placed(a, 0, 0, 0)), dock = listOf(x, y))
        val r = move(start, a.id, DropTarget.DockSlot(1)) // between x and y
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        assertThat(r.layout!!.dock.map { it.id }).containsExactly(x.id, a.id, y.id).inOrder()
    }

    @Test fun dock_reorder_moves_icon_to_a_middle_index() {
        val a = app("a")
        val b = app("b", "pb")
        val c = app("c", "pc")
        val start = layout(dock = listOf(a, b, c))
        // Move a to index 1 (exclusive of a): dockWithoutSource=[b,c], insert at 1 → [b,a,c].
        val r = move(start, a.id, DropTarget.DockSlot(1))
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        assertThat(r.layout!!.dock.map { it.id }).containsExactly(b.id, a.id, c.id).inOrder()
    }

    @Test fun reorder_within_a_full_dock_is_allowed() {
        // A full dock still reorders — excluding the source frees a slot (not DOCK_FULL).
        val dockItems = (0 until grid.columns).map { app("d$it", "pd$it") }
        val start = layout(dock = dockItems)
        val r = move(start, dockItems.first().id, DropTarget.DockSlot(grid.columns))
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val expected = dockItems.drop(1).map { it.id } + dockItems.first().id
        assertThat(r.layout!!.dock.map { it.id }).containsExactlyElementsIn(expected).inOrder()
    }

    @Test fun dock_item_to_own_slot_is_noop() {
        val a = app("a")
        val start = layout(dock = listOf(a))
        val r = move(start, a.id, DropTarget.DockSlot(0))
        assertThat(r).isEqualTo(MoveResult.NoOp)
    }

    @Test fun dock_item_dropped_past_last_icon_moves_to_end() {
        // A1-13: the UI passes the source-inclusive dock size as the slot when the
        // finger is past the last dock icon; an in-dock source must append (moved
        // to the end), not be rejected as OFF_GRID.
        val a = app("a")
        val b = app("b", "pb")
        val c = app("c", "pc")
        val start = layout(dock = listOf(a, b, c))
        val r = move(start, a.id, DropTarget.DockSlot(3)) // slot == dock.size (past last)
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        assertThat(r.layout!!.dock.map { it.id }).containsExactly(b.id, c.id, a.id).inOrder()
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

    // ---- Grid reorder-insert (DropTarget.GridInsert) — Launcher3-style shift ----

    // grid is 4×6 = 24 cells; li = y*4 + x.
    private fun HomeLayout.idAtLi(li: Int): ItemId? =
        items.firstOrNull { it.pos == CellPos(0, li % 4, li / 4) }?.item?.id

    @Test fun insert_shifts_occupant_right_and_absorbs_the_gap() {
        val a = app("a"); val b = app("b", "pb"); val c = app("c", "pc")
        // A@li0, B@li1, gap@li2, C@li3.
        val start = layout(items = listOf(placed(a, 0, 0, 0), placed(b, 0, 1, 0), placed(c, 0, 3, 0)))
        val r = move(start, c.id, DropTarget.GridInsert(0, 1)) // insert C at li1 (on B), not a folder
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.idAtLi(0)).isEqualTo(a.id)
        assertThat(out.idAtLi(1)).isEqualTo(c.id) // C inserted
        assertThat(out.idAtLi(2)).isEqualTo(b.id) // B shifted into the gap
        assertThat(out.items.none { it.item is HomeItem.Folder }).isTrue() // no folder
    }

    @Test fun insert_on_an_empty_cell_just_places() {
        val a = app("a"); val b = app("b", "pb")
        val start = layout(items = listOf(placed(a, 0, 0, 0), placed(b, 0, 1, 0)))
        val r = move(start, b.id, DropTarget.GridInsert(0, 10)) // li10 is empty
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.idAtLi(0)).isEqualTo(a.id)
        assertThat(out.idAtLi(10)).isEqualTo(b.id)
        assertThat(out.items).hasSize(2)
    }

    @Test fun insert_before_first_shifts_a_full_row_wrapping_to_next_row() {
        val row = listOf(app("a"), app("b", "pb"), app("c", "pc"), app("d", "pd"))
        val e = app("e", "pe")
        // Row 0 full (li0..3); E comes from the dock, inserted at li0.
        val start = layout(
            items = row.mapIndexed { i, it -> placed(it, 0, i, 0) },
            dock = listOf(e),
        )
        val r = move(start, e.id, DropTarget.GridInsert(0, 0))
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.idAtLi(0)).isEqualTo(e.id)
        assertThat(out.idAtLi(1)).isEqualTo(ItemId("a"))
        assertThat(out.idAtLi(4)).isEqualTo(ItemId("d")) // wrapped to row 1, col 0
        assertThat(out.dock).isEmpty()
    }

    @Test fun insert_at_a_dense_tail_shifts_back_into_the_preceding_gap() {
        val x = app("x", "px")
        val y = app("y", "py")
        // Only li23 (last cell) occupied; Y from the dock inserted there. The tail is
        // dense (nothing after li23) but li22 is free → X slides back, no new page.
        val start = layout(items = listOf(placed(x, 0, 3, 5)), dock = listOf(y))
        val r = move(start, y.id, DropTarget.GridInsert(0, 23))
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.pages).isEqualTo(1) // no phantom page
        assertThat(out.idAtLi(22)).isEqualTo(x.id) // X shifted back one cell
        assertThat(out.idAtLi(23)).isEqualTo(y.id)
    }

    @Test fun insert_on_a_completely_full_page_overflows_last_occupant_to_next_page() {
        // Every cell 0..23 on page 0 occupied; Z comes from the dock, inserted at li23.
        val occupants = (0 until 24).map { app("f$it", "pf$it") }
        val z = app("z", "pz")
        val start = layout(
            items = occupants.mapIndexed { li, it -> placed(it, 0, li % 4, li / 4) },
            dock = listOf(z),
        )
        val r = move(start, z.id, DropTarget.GridInsert(0, 23))
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.pages).isEqualTo(2)
        assertThat(out.idAtLi(23)).isEqualTo(z.id)
        // The former li23 occupant spills to page 1's first cell.
        assertThat(out.items.first { it.item.id == ItemId("f23") }.pos).isEqualTo(CellPos(1, 0, 0))
        assertThat(out.dock).isEmpty()
    }

    @Test fun forward_reorder_on_a_full_page_shifts_back_without_a_phantom_page() {
        // Regression for the review finding: full page, drag the first icon (li0) to the
        // last slot (li23). Removing the source frees li0, so the run li1..li23 slides
        // back into li0..li22 and the source lands at li23 — no overflow, no hole.
        val items = (0 until 24).map { app("g$it", "pg$it") }
        val start = layout(items = items.mapIndexed { li, it -> placed(it, 0, li % 4, li / 4) })
        val r = move(start, ItemId("g0"), DropTarget.GridInsert(0, 23))
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.pages).isEqualTo(1) // no phantom page
        assertThat(out.items).hasSize(24) // count preserved, nothing dropped
        assertThat(out.idAtLi(0)).isEqualTo(ItemId("g1")) // g1..g23 slid back one cell
        assertThat(out.idAtLi(22)).isEqualTo(ItemId("g23"))
        assertThat(out.idAtLi(23)).isEqualTo(ItemId("g0")) // dragged item now last
    }

    @Test fun gridinsert_off_grid_targets_are_rejected() {
        val a = app("a")
        val start = layout(items = listOf(placed(a, 0, 0, 0))) // pages = 1, cells = 24
        for (t in listOf(
            DropTarget.GridInsert(page = 5, index = 0), // page > pages
            DropTarget.GridInsert(page = 0, index = -1), // negative index
            DropTarget.GridInsert(page = 0, index = 25), // index > cells
        )) {
            val r = move(start, a.id, t)
            assertThat(r).isInstanceOf(MoveResult.Rejected::class.java)
            assertThat((r as MoveResult.Rejected).reason).isEqualTo(MoveResult.Reason.OFF_GRID)
        }
    }

    @Test fun gridinsert_past_the_last_cell_appends_at_first_free() {
        val a = app("a"); val b = app("b", "pb")
        // b from the dock, index == cells (24) → append branch → first free cell (li1).
        val start = layout(items = listOf(placed(a, 0, 0, 0)), dock = listOf(b))
        val r = move(start, b.id, DropTarget.GridInsert(0, 24))
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.idAtLi(0)).isEqualTo(a.id)
        assertThat(out.idAtLi(1)).isEqualTo(b.id) // appended at the first free cell
        assertThat(out.dock).isEmpty()
    }

    @Test fun insert_at_own_position_is_a_noop() {
        val a = app("a")
        val start = layout(items = listOf(placed(a, 0, 0, 0)))
        val r = move(start, a.id, DropTarget.GridInsert(0, 0))
        assertThat(r).isEqualTo(MoveResult.NoOp)
    }

    // ---- Programmer-error precondition (§MIU-INV-2) ----

    @Test fun unknown_moving_id_is_noop() {
        val a = app("a")
        val start = layout(items = listOf(placed(a, 0, 0, 0)))
        val r = move(start, ItemId("ghost"), DropTarget.Cell(CellPos(0, 1, 1)))
        assertThat(r).isEqualTo(MoveResult.NoOp)
    }
}
