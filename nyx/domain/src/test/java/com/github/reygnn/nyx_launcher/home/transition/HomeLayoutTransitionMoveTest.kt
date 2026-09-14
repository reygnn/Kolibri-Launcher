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
import com.github.reygnn.nyx_launcher.home.model.Span
import com.github.reygnn.nyx_launcher.home.model.firstFreeCell
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
        val out = fc.layout
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

    @Test fun dock_app_dropped_onto_a_grid_app_creates_a_folder() {
        // Folder creation where the DRAGGED source lives in the dock, not the grid: the
        // removing() step must pull it out of the dock, and the new folder lands on the
        // target's cell (target key first, then the dragged dock app). The other
        // folder-creation tests all drag a grid source, so this pins the dock-source path.
        val d = app("d", "pd")
        val b = app("b", "pb")
        val start = layout(items = listOf(placed(b, 0, 1, 0)), dock = listOf(d))
        val r = move(start, d.id, DropTarget.Cell(CellPos(0, 1, 0))) // dock app d onto grid app b
        assertThat(r).isInstanceOf(MoveResult.FolderCreated::class.java)
        val out = r.layout!!
        assertThat(out.dock).isEmpty() // d pulled out of the dock
        val placedFolder = out.items.single()
        assertThat(placedFolder.pos).isEqualTo(CellPos(0, 1, 0))
        val f = placedFolder.item as HomeItem.Folder
        assertThat(f.members).containsExactly(ck("pb"), ck("pd")).inOrder() // target, then dragged
    }

    @Test fun dock_app_dropped_onto_a_grid_folder_is_added_and_leaves_the_dock() {
        // Add-to-folder with a dock source: d is appended to the grid folder and vacates
        // the dock. Mirrors dock_app_dropped_onto_a_grid_app_creates_a_folder for the
        // folder-target branch of moveToCell.
        val f = folder("f", ck("pb"), ck("pc"))
        val d = app("d", "pd")
        val start = layout(items = listOf(placed(f, 0, 1, 0)), dock = listOf(d))
        val r = move(start, d.id, DropTarget.Cell(CellPos(0, 1, 0)))
        assertThat(r).isInstanceOf(MoveResult.AddedToFolder::class.java)
        val out = r.layout!!
        assertThat(out.dock).isEmpty()
        val fOut = out.items.first { it.item.id == f.id }.item as HomeItem.Folder
        assertThat(fOut.members).containsExactly(ck("pb"), ck("pc"), ck("pd")).inOrder()
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
        // Negative coordinates (defensive/total-function contract): offGridReason's lower
        // bounds must reject too, not just the over-bounds cases above.
        assertThat(move(start, a.id, DropTarget.Cell(CellPos(0, -1, 0)))) // negative x
            .isEqualTo(MoveResult.Rejected(MoveResult.Reason.OFF_GRID))
        assertThat(move(start, a.id, DropTarget.Cell(CellPos(0, 0, -1)))) // negative y
            .isEqualTo(MoveResult.Rejected(MoveResult.Reason.OFF_GRID))
        assertThat(move(start, a.id, DropTarget.Cell(CellPos(-1, 0, 0)))) // negative page
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

    @Test fun reorder_within_an_over_capacity_dock_is_allowed() {
        // A dock OVER capacity (size > columns) is a transient state after a grid shrink,
        // before the regridder re-homes the overflow. The user must still be able to tidy
        // it: a reorder never grows the dock (source excluded then re-added), so it is
        // allowed even while over capacity — only an INCOMING item is capped. Regression
        // for the UX bug where the capacity guard also blocked reordering an over-full dock.
        val overCap = (0..grid.columns).map { app("d$it", "pd$it") } // columns + 1 icons
        val start = layout(dock = overCap)
        val r = move(start, overCap.first().id, DropTarget.DockSlot(grid.columns)) // move d0 to the end
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val expected = overCap.drop(1).map { it.id } + overCap.first().id
        assertThat(r.layout!!.dock.map { it.id }).containsExactly(*expected.toTypedArray()).inOrder()
        assertThat(r.layout!!.dock).hasSize(grid.columns + 1) // still over capacity — the reorder didn't shrink it
    }

    @Test fun incoming_item_into_an_over_capacity_dock_is_still_rejected() {
        // The flip side of the fix: the capacity guard must still bite for an INCOMING
        // item (a grid source), which WOULD grow an already-over-capacity dock further.
        val overCap = (0..grid.columns).map { app("d$it", "pd$it") } // columns + 1 icons
        val a = app("a", "pa")
        val start = layout(items = listOf(placed(a, 0, 0, 0)), dock = overCap)
        val r = move(start, a.id, DropTarget.DockSlot(0))
        assertThat(r).isEqualTo(MoveResult.Rejected(MoveResult.Reason.DOCK_FULL))
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

    @Test fun dock_index_past_the_source_inclusive_size_is_rejected() {
        // The UI never counts more slots than the (source-inclusive) dock size, so an
        // index beyond it is out of range — reject as OFF_GRID rather than silently
        // clamp-and-append (which would mask a bug and misplace the icon).
        val a = app("a")
        val x = app("x", "px")
        val start = layout(items = listOf(placed(a, 0, 0, 0)), dock = listOf(x)) // dock.size = 1
        val r = move(start, a.id, DropTarget.DockSlot(2)) // 2 > dock.size (1)
        assertThat(r).isEqualTo(MoveResult.Rejected(MoveResult.Reason.OFF_GRID))
    }

    @Test fun dock_grid_source_appends_at_exactly_dock_size() {
        // Boundary just below the rejection: a grid source dropped at index == dock.size
        // is the legitimate append and must succeed.
        val a = app("a")
        val x = app("x", "px")
        val start = layout(items = listOf(placed(a, 0, 0, 0)), dock = listOf(x)) // dock.size = 1
        val r = move(start, a.id, DropTarget.DockSlot(1)) // == dock.size → append
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        assertThat(r.layout!!.dock.map { it.id }).containsExactly(x.id, a.id).inOrder()
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
            DropTarget.GridInsert(page = -1, index = 0), // negative page
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

    @Test fun gridinsert_past_the_last_cell_lands_in_an_earlier_interior_hole() {
        // The append branch (index >= cells) delegates to firstFreeCellFrom, which fills the
        // first ROW-MAJOR hole from this page on — so a drop "past the last cell" of a page
        // that has an interior gap lands IN that gap, not visually at the end.
        // gridinsert_past_the_last_cell_appends_at_first_free only has a hole at the natural
        // next slot; this pins the interior-hole case (a@li0, hole@li1, c@li2).
        val a = app("a"); val c = app("c", "pc"); val b = app("b", "pb")
        val start = layout(items = listOf(placed(a, 0, 0, 0), placed(c, 0, 2, 0)), dock = listOf(b))
        val r = move(start, b.id, DropTarget.GridInsert(0, grid.columns * grid.rows)) // index == cells
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.idAtLi(0)).isEqualTo(a.id)
        assertThat(out.idAtLi(1)).isEqualTo(b.id) // filled the interior hole, not appended after c
        assertThat(out.idAtLi(2)).isEqualTo(c.id)
        assertThat(out.dock).isEmpty()
    }

    @Test fun insert_at_own_position_is_a_noop() {
        val a = app("a")
        val start = layout(items = listOf(placed(a, 0, 0, 0)))
        val r = move(start, a.id, DropTarget.GridInsert(0, 0))
        assertThat(r).isEqualTo(MoveResult.NoOp)
    }

    @Test fun insert_into_the_middle_of_a_full_page_block_shifts_and_overflows_the_last() {
        // Every cell 0..23 on page 0 occupied; insert Z (from the dock) at li10 — NOT the
        // last cell, so the whole tail [li10..li23] must block-shift +1 and only the last
        // occupant (f23) spills to the next page. The existing full-page test drops at
        // li23 where the shift loop moves nothing; this exercises the real block shift.
        val occupants = (0 until 24).map { app("f$it", "pf$it") }
        val z = app("z", "pz")
        val start = layout(
            items = occupants.mapIndexed { li, it -> placed(it, 0, li % 4, li / 4) },
            dock = listOf(z),
        )
        val r = move(start, z.id, DropTarget.GridInsert(0, 10))
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.pages).isEqualTo(2)
        assertThat(out.items).hasSize(25) // 24 occupants + Z, nothing lost
        assertThat(out.idAtLi(9)).isEqualTo(ItemId("f9")) // before insert point — unchanged
        assertThat(out.idAtLi(10)).isEqualTo(z.id) // Z inserted
        assertThat(out.idAtLi(11)).isEqualTo(ItemId("f10")) // f10 shifted up one
        assertThat(out.idAtLi(23)).isEqualTo(ItemId("f22")) // f22 shifted into the last cell
        // f23, the former last occupant, spills to page 1's first cell.
        assertThat(out.items.first { it.item.id == ItemId("f23") }.pos).isEqualTo(CellPos(1, 0, 0))
        assertThat(out.dock).isEmpty()
    }

    @Test fun overflow_skips_a_full_next_page_and_lands_on_a_fresh_page() {
        // Page 0 AND page 1 both full; insert Z at li23 of page 0. The overflow occupant
        // can't fit on page 1 (full) → firstFreeCellFrom must scan past it and add page 2.
        val page0 = (0 until 24).map { app("f$it", "pf$it") }
        val page1 = (0 until 24).map { app("g$it", "pg$it") }
        val z = app("z", "pz")
        val start = layout(
            items = page0.mapIndexed { li, it -> placed(it, 0, li % 4, li / 4) } +
                page1.mapIndexed { li, it -> placed(it, 1, li % 4, li / 4) },
            dock = listOf(z),
            pages = 2,
        )
        val r = move(start, z.id, DropTarget.GridInsert(0, 23))
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.pages).isEqualTo(3)
        assertThat(out.idAtLi(23)).isEqualTo(z.id) // Z at page 0 li23
        // The former li23 occupant (f23) skips the full page 1 and lands on page 2.
        assertThat(out.items.first { it.item.id == ItemId("f23") }.pos).isEqualTo(CellPos(2, 0, 0))
        // Page 1 is untouched.
        assertThat(out.items.first { it.item.id == ItemId("g0") }.pos).isEqualTo(CellPos(1, 0, 0))
    }

    @Test fun overflow_lands_in_a_partially_full_next_pages_hole_without_adding_a_page() {
        // The realistic middle case between the two extremes above: page 0 full, page 1
        // exists but holds only li0 & li1 (a hole from li2 on). Inserting Z into the
        // MIDDLE of full page 0 spills the last occupant (f23), which must drop into page
        // 1's FIRST hole (li2) — not a fresh page, and pages stays 2.
        val page0 = (0 until 24).map { app("f$it", "pf$it") }
        val page1 = listOf(app("g0", "pg0"), app("g1", "pg1"))
        val z = app("z", "pz")
        val start = layout(
            items = page0.mapIndexed { li, it -> placed(it, 0, li % 4, li / 4) } +
                page1.mapIndexed { li, it -> placed(it, 1, li % 4, li / 4) },
            dock = listOf(z),
            pages = 2,
        )
        val r = move(start, z.id, DropTarget.GridInsert(0, 10))
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.pages).isEqualTo(2) // the existing hole absorbed the overflow
        val byId = out.items.associate { it.item.id to it.pos }
        assertThat(byId[ItemId("z")]).isEqualTo(CellPos(0, 2, 2)) // li10 = x2,y2
        assertThat(byId[ItemId("f23")]).isEqualTo(CellPos(1, 2, 0)) // spilled into page 1's hole li2
        assertThat(byId[ItemId("g0")]).isEqualTo(CellPos(1, 0, 0)) // page 1 existing icons untouched
        assertThat(byId[ItemId("g1")]).isEqualTo(CellPos(1, 1, 0))
        assertThat(out.dock).isEmpty()
    }

    @Test fun grid_insert_onto_a_brand_new_trailing_page_appends_a_page() {
        // page == pages with a small in-range index: the append branch adds a page and
        // lands at that page's first free cell (not a rejection).
        val a = app("a")
        val b = app("b", "pb")
        val start = layout(items = listOf(placed(a, 0, 0, 0)), dock = listOf(b), pages = 1)
        val r = move(start, b.id, DropTarget.GridInsert(page = 1, index = 0)) // page == pages
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.pages).isEqualTo(2)
        assertThat(out.items.first { it.item.id == b.id }.pos).isEqualTo(CellPos(1, 0, 0))
        assertThat(out.dock).isEmpty()
    }

    @Test fun move_to_a_cell_on_another_existing_page() {
        // Cross-page move: source on page 0, target an empty cell on an existing page 1.
        val a = app("a")
        val b = app("b", "pb")
        val start = layout(items = listOf(placed(a, 0, 0, 0), placed(b, 1, 0, 0)), pages = 2)
        val r = move(start, a.id, DropTarget.Cell(CellPos(1, 1, 1)))
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.pages).isEqualTo(2) // no new page — page 1 already existed
        assertThat(out.items.first { it.item.id == a.id }.pos).isEqualTo(CellPos(1, 1, 1))
        assertThat(out.items.first { it.item.id == b.id }.pos).isEqualTo(CellPos(1, 0, 0))
    }

    @Test fun app_dropped_onto_a_folder_that_already_contains_it_is_a_noop() {
        // IHM-INV-7 guard (line 190): a top-level app whose key is already a member of
        // the target folder must NOT be appended again — it collapses to NoOp.
        val f = folder("f", ck("pa"), ck("pb"))
        val a = app("a", "pa") // same key pa as a folder member
        val start = layout(items = listOf(placed(a, 0, 0, 0), placed(f, 0, 1, 0)))
        val r = move(start, a.id, DropTarget.Cell(CellPos(0, 1, 0)))
        assertThat(r).isEqualTo(MoveResult.NoOp)
    }

    @Test fun a_folder_grows_past_the_grid_capacity_and_is_never_rejected() {
        // Design decision (mirrors modern Launcher3's paged folders, NOT the old
        // reject-on-full cap): folders are uncapped so no app is ever lost. A folder
        // already larger than a whole page still accepts one more member.
        val members = (0 until 30).map { ck("m$it") } // 30 > 24 cells/page
        val big = folder("big", *members.toTypedArray())
        val a = app("a", "pa")
        val start = layout(items = listOf(placed(a, 0, 0, 0), placed(big, 0, 1, 0)))
        val r = move(start, a.id, DropTarget.Cell(CellPos(0, 1, 0)))
        assertThat(r).isInstanceOf(MoveResult.AddedToFolder::class.java)
        val fOut = r.layout!!.items.first { it.item.id == big.id }.item as HomeItem.Folder
        assertThat(fOut.members).hasSize(31)
        assertThat(fOut.members.last()).isEqualTo(ck("pa"))
    }

    // ---- Page cap (HomeLayout.MAX_PAGES) ----

    @Test fun dropping_onto_a_cell_beyond_the_page_cap_is_rejected() {
        // A layout already at the cap has no landing page beyond it: a Cell drop onto
        // page index == MAX_PAGES is off-grid.
        val a = app("a")
        val start = layout(items = listOf(placed(a, 0, 0, 0)), pages = HomeLayout.MAX_PAGES)
        val r = move(start, a.id, DropTarget.Cell(CellPos(HomeLayout.MAX_PAGES, 0, 0)))
        assertThat(r).isEqualTo(MoveResult.Rejected(MoveResult.Reason.OFF_GRID))
    }

    @Test fun dropping_onto_the_last_page_within_the_cap_still_creates_it() {
        // Boundary just under the cap: pages == MAX_PAGES - 1, dropping on the landing
        // page (index == MAX_PAGES - 1) legitimately creates the final allowed page.
        val a = app("a")
        val start = layout(items = listOf(placed(a, 0, 0, 0)), pages = HomeLayout.MAX_PAGES - 1)
        val r = move(start, a.id, DropTarget.Cell(CellPos(HomeLayout.MAX_PAGES - 1, 0, 0)))
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.pages).isEqualTo(HomeLayout.MAX_PAGES)
        assertThat(out.items.single().pos).isEqualTo(CellPos(HomeLayout.MAX_PAGES - 1, 0, 0))
    }

    @Test fun grid_insert_onto_a_page_beyond_the_cap_is_rejected() {
        val a = app("a")
        val start = layout(items = listOf(placed(a, 0, 0, 0)), pages = HomeLayout.MAX_PAGES)
        val r = move(start, a.id, DropTarget.GridInsert(HomeLayout.MAX_PAGES, 0))
        assertThat(r).isEqualTo(MoveResult.Rejected(MoveResult.Reason.OFF_GRID))
    }

    @Test fun overflow_at_the_page_cap_is_rejected_not_a_tenth_page() {
        // 1x1 grid = one cell per page; fill all MAX_PAGES pages, then a reorder-insert
        // whose spilled occupant has nowhere within the cap → the whole move is rejected
        // (no app dropped, no page beyond the cap created).
        val tinyGrid = GridSpec(columns = 1, rows = 1)
        val occupants = (0 until HomeLayout.MAX_PAGES).map { app("f$it", "pf$it") }
        val z = app("z", "pz")
        val start = HomeLayout(
            tinyGrid,
            pages = HomeLayout.MAX_PAGES,
            items = occupants.mapIndexed { p, it -> PlacedItem(it, CellPos(p, 0, 0)) },
            dock = listOf(z),
        )
        val r = move(start, z.id, DropTarget.GridInsert(HomeLayout.MAX_PAGES - 1, 0))
        assertThat(r).isEqualTo(MoveResult.Rejected(MoveResult.Reason.OFF_GRID))
    }

    @Test fun grid_insert_append_onto_a_completely_full_home_is_rejected() {
        // The APPEND branch (index >= cells) rejects when firstFreeCellFrom finds no cell
        // within the page cap — distinct from overflow_at_the_page_cap_is_rejected, which
        // exercises the shift/overflow branch (HomeLayoutTransition.kt:151). 1x1 grid, all
        // MAX_PAGES pages full, GridInsert past the last cell → OFF_GRID (line 89).
        val tiny = GridSpec(columns = 1, rows = 1)
        val occupants = (0 until HomeLayout.MAX_PAGES).map { app("f$it", "pf$it") }
        val z = app("z", "pz")
        val start = HomeLayout(
            tiny,
            pages = HomeLayout.MAX_PAGES,
            items = occupants.mapIndexed { p, it -> PlacedItem(it, CellPos(p, 0, 0)) },
            dock = listOf(z),
        )
        // index == cells (1) → append branch; page in range but every page is full.
        val r = HomeLayoutTransition.move(start, z.id, DropTarget.GridInsert(HomeLayout.MAX_PAGES - 1, 1), newId::next)
        assertThat(r).isEqualTo(MoveResult.Rejected(MoveResult.Reason.OFF_GRID))
    }

    @Test fun add_to_home_onto_a_full_home_is_rejected() {
        // Coupling regression for MainActivity.addToHome (line 1087): firstFreeCell() is
        // uncapped and, on a home with all MAX_PAGES pages full, returns
        // CellPos(MAX_PAGES, 0, 0) — an off-cap page. Feeding that straight into a move
        // (as addToHome does via place → moveToCell) MUST be rejected as OFF_GRID, so
        // "add to home" silently no-ops on a full home instead of landing on a page the
        // pager never renders. A 1x1 grid makes MAX_PAGES items fill everything.
        val tiny = GridSpec(columns = 1, rows = 1)
        val occupants = (0 until HomeLayout.MAX_PAGES).map { app("f$it", "pf$it") }
        val full = HomeLayout(
            tiny,
            pages = HomeLayout.MAX_PAGES,
            items = occupants.mapIndexed { p, it -> PlacedItem(it, CellPos(p, 0, 0)) },
            dock = emptyList(),
        )
        val landing = full.firstFreeCell()
        assertThat(landing).isEqualTo(CellPos(HomeLayout.MAX_PAGES, 0, 0)) // uncapped, off-cap page
        val r = HomeLayoutTransition.move(full, ItemId("f0"), DropTarget.Cell(landing), newId::next)
        assertThat(r).isEqualTo(MoveResult.Rejected(MoveResult.Reason.OFF_GRID))
    }

    @Test fun insert_prefers_a_forward_gap_over_a_closer_backward_gap() {
        // The shift is forward-FIRST, not nearest-gap (HomeLayoutTransition.kt:106-118):
        // free cells at li3 (backward, distance 1) and li7 (forward, distance 3). Inserting
        // X (from the dock) at the occupied li4 must shift the block [4,7) forward and leave
        // li3 untouched — proving the forward gap wins even though li3 is nearer.
        val occupants = listOf(0, 1, 2, 4, 5, 6).map { li -> app("o$li", "po$li") to li }
        val x = app("x", "px")
        val start = layout(
            items = occupants.map { (a, li) -> placed(a, 0, li % 4, li / 4) },
            dock = listOf(x),
        )
        val r = move(start, x.id, DropTarget.GridInsert(0, 4))
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.pages).isEqualTo(1)
        assertThat(out.idAtLi(3)).isNull() // backward gap deliberately NOT consumed
        assertThat(out.idAtLi(4)).isEqualTo(x.id) // X inserted
        assertThat(out.idAtLi(5)).isEqualTo(ItemId("o4")) // block shifted forward one cell
        assertThat(out.idAtLi(6)).isEqualTo(ItemId("o5"))
        assertThat(out.idAtLi(7)).isEqualTo(ItemId("o6")) // last of the block lands in the forward gap
        assertThat(out.dock).isEmpty()
    }

    @Test fun reorder_shift_on_a_non_zero_page_stays_on_that_page() {
        // Every other reorder/shift test runs on page 0; this pins that the shift honours
        // the target page (cellOf(page, …), the page-scoped occupied map, and the
        // filterNot { page == this } reconstruction) instead of silently touching page 0.
        val filler = app("filler", "pfill") // keeps page 0 alive and untouched
        val a = app("a", "pa"); val b = app("b", "pb"); val c = app("c", "pc")
        val start = layout(
            items = listOf(
                placed(filler, 0, 0, 0),
                placed(a, 1, 0, 0), // page 1, li0
                placed(b, 1, 1, 0), // page 1, li1
                placed(c, 1, 3, 0), // page 1, li3 (gap at li2)
            ),
            pages = 2,
        )
        val r = move(start, c.id, DropTarget.GridInsert(1, 1)) // insert C at page-1 li1 (on B)
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.pages).isEqualTo(2)
        val byId = out.items.associate { it.item.id to it.pos }
        assertThat(byId[filler.id]).isEqualTo(CellPos(0, 0, 0)) // page 0 untouched
        assertThat(byId[a.id]).isEqualTo(CellPos(1, 0, 0)) // before the insert point — unchanged
        assertThat(byId[c.id]).isEqualTo(CellPos(1, 1, 0)) // C inserted at page-1 li1
        assertThat(byId[b.id]).isEqualTo(CellPos(1, 2, 0)) // B shifted into the page-1 gap
    }

    @Test fun dock_negative_index_is_rejected() {
        // moveToDock guards index < 0 first (HomeLayoutTransition.kt:210): the total
        // function rejects rather than crashing on a MutableList.add(-1, …).
        val a = app("a")
        val x = app("x", "px")
        val start = layout(items = listOf(placed(a, 0, 0, 0)), dock = listOf(x)) // dock not full
        val r = move(start, a.id, DropTarget.DockSlot(-1))
        assertThat(r).isEqualTo(MoveResult.Rejected(MoveResult.Reason.OFF_GRID))
    }

    @Test fun dock_reorder_to_its_current_slot_in_a_multi_item_dock_is_a_noop() {
        // dock_item_to_own_slot_is_noop covers a single-item dock; this pins the
        // newDock == dock short-circuit (HomeLayoutTransition.kt:235) for a MULTI-item
        // dock: moving the middle icon to the slot it already occupies rebuilds the
        // identical list → NoOp (one icon, a, sits to b's left → source-exclusive index 1).
        val a = app("a"); val b = app("b", "pb"); val c = app("c", "pc")
        val start = layout(dock = listOf(a, b, c))
        val r = move(start, b.id, DropTarget.DockSlot(1))
        assertThat(r).isEqualTo(MoveResult.NoOp)
    }

    @Test fun grid_insert_with_a_folder_source_reorders_like_any_item() {
        // Every other GridInsert test drags an app; insertOnGrid is type-agnostic, so a
        // FOLDER reorder-inserted onto an occupied cell must shift the occupant and land the
        // folder (never fold — foldering is the Cell centre path), its members untouched.
        val a = app("a"); val b = app("b", "pb"); val f = folder("f", ck("pm"), ck("pn"))
        // a@li0, b@li1, gap@li2, folder@li3.
        val start = layout(items = listOf(placed(a, 0, 0, 0), placed(b, 0, 1, 0), placed(f, 0, 3, 0)))
        val r = move(start, f.id, DropTarget.GridInsert(0, 1)) // insert folder at li1 (on b)
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.idAtLi(0)).isEqualTo(a.id)
        assertThat(out.idAtLi(1)).isEqualTo(f.id) // folder inserted
        assertThat(out.idAtLi(2)).isEqualTo(b.id) // b shifted into the gap
        val fOut = out.items.first { it.item.id == f.id }.item as HomeItem.Folder
        assertThat(fOut.members).containsExactly(ck("pm"), ck("pn")).inOrder() // members intact
        assertThat(out.items.count { it.item is HomeItem.Folder }).isEqualTo(1) // no new folder created
    }

    // ---- Span preservation across a move (A1-17, v2 widget lift) ----
    // v1 never stores a span > 1×1, so these guard the forward-compat contract that the
    // regridder already keeps (HomeLayoutRegridderTest.a_relocated_off_grid_items_span_is_
    // preserved): dragging a widget must NOT shrink it back to 1×1. Before the fix every
    // move built PlacedItem(source, pos) with a default span and silently dropped it.

    @Test fun move_to_an_empty_cell_preserves_the_source_span() {
        val wide = PlacedItem(app("w", "pw"), CellPos(0, 0, 0), Span(2, 2))
        val start = layout(items = listOf(wide))
        val r = move(start, ItemId("w"), DropTarget.Cell(CellPos(0, 1, 1)))
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val moved = r.layout!!.items.single { it.item.id == ItemId("w") }
        assertThat(moved.pos).isEqualTo(CellPos(0, 1, 1))
        assertThat(moved.span).isEqualTo(Span(2, 2)) // span survives the relocation
    }

    @Test fun grid_insert_reorder_preserves_the_source_span() {
        // Wide source reorder-inserted onto an occupied cell (the shift path): the shifted
        // occupant keeps its own span via copy(), and the moved source keeps its span too.
        val wide = PlacedItem(app("w", "pw"), CellPos(0, 0, 0), Span(2, 2))
        val b = app("b", "pb")
        // w@li0 (wide), b@li1, gap@li2.
        val start = layout(items = listOf(wide, placed(b, 0, 1, 0)))
        val r = move(start, ItemId("w"), DropTarget.GridInsert(0, 1)) // insert w at li1 (on b)
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        val movedW = out.items.single { it.item.id == ItemId("w") }
        assertThat(movedW.pos).isEqualTo(CellPos(0, 1, 0)) // landed at li1
        assertThat(movedW.span).isEqualTo(Span(2, 2)) // span preserved through the shift path
        assertThat(out.items.single { it.item.id == b.id }.pos).isEqualTo(CellPos(0, 2, 0)) // b shifted
    }

    @Test fun folder_creation_from_a_wide_source_uses_a_default_span() {
        // A folder born from a drop is a fresh 1×1 item (MOVE_ITEM_SPEC §7-D1); the wide
        // dragged app's span must NOT leak onto the new folder. Pins the deliberate
        // default-span decision on the FolderCreated path.
        val wide = PlacedItem(app("w", "pw"), CellPos(0, 0, 0), Span(2, 2))
        val target = app("t", "pt")
        val start = layout(items = listOf(wide, placed(target, 0, 1, 0)))
        val r = move(start, ItemId("w"), DropTarget.Cell(CellPos(0, 1, 0))) // w onto t → folder
        assertThat(r).isInstanceOf(MoveResult.FolderCreated::class.java)
        val folder = r.layout!!.items.single()
        assertThat(folder.item).isInstanceOf(HomeItem.Folder::class.java)
        assertThat(folder.span).isEqualTo(Span()) // default 1×1, not the source's 2×2
    }

    // ---- Programmer-error precondition (§MIU-INV-2) ----

    @Test fun unknown_moving_id_is_noop() {
        val a = app("a")
        val start = layout(items = listOf(placed(a, 0, 0, 0)))
        val r = move(start, ItemId("ghost"), DropTarget.Cell(CellPos(0, 1, 1)))
        assertThat(r).isEqualTo(MoveResult.NoOp)
    }

    // ---- `pages` is never trimmed downward by a move (A1) ----

    @Test fun pulling_the_last_item_off_the_top_page_does_not_trim_pages() {
        // A1: move only ever GROWS `pages` (append/landing-page), never recomputes it
        // downward. Dragging the sole resident of the highest page down to a lower page
        // leaves `pages` inflated until HomeLayoutReconciler Pass 5 (trailing-page trim)
        // runs. This is correct, not a bug: `layout.pages` drives NO visible page — the
        // pager and page-dots both derive their count from renderedPageCount() (position-
        // derived, MainActivity.kt:658/835), so an inflated `pages` is inert and unreachable,
        // and trailing-trim is deliberately the reconciler's job (Pass 5 keeps interior empty
        // pages). Recomputing here would be redundant hot-path work that risks diverging from
        // that trim policy. This pins the move core deliberately leaving `pages` alone.
        val onTop = app("top", "ptop")
        val onBase = app("base", "pbase")
        val start = layout(items = listOf(placed(onBase, 0, 0, 0), placed(onTop, 1, 0, 0)), pages = 2)
        val r = move(start, onTop.id, DropTarget.Cell(CellPos(0, 1, 1))) // pull it down to page 0
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.pages).isEqualTo(2) // NOT trimmed to 1 despite page 1 now being empty
        assertThat(out.items.first { it.item.id == onTop.id }.pos).isEqualTo(CellPos(0, 1, 1))
        assertThat(out.items.none { it.pos.page == 1 }).isTrue() // page 1 is empty but still counted
    }
}
