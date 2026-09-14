package com.github.reygnn.nyx_launcher.home.transition

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.launcher.core.ComponentKey
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

    @Test fun extract_to_out_of_range_grid_insert_lands_at_first_free() {
        // A drop on the right edge of the last grid cell yields GridInsert(index==cells),
        // which is out of range — the extraction must still land (first free cell), not
        // be silently rejected as OFF_GRID.
        val f = folder("f", ck("pa"), ck("pb"), ck("pc"), page = 0, x = 0, y = 0)
        val start = layout(items = listOf(f))
        val cells = grid.columns * grid.rows
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("f"), ck("pb"), DropTarget.GridInsert(0, cells), seq("x")::next,
        )
        assertThat(r).isInstanceOf(FolderEditResult.Extracted::class.java)
        val out = r.layout!!
        assertThat(out.items.any { (it.item as? HomeItem.App)?.key == ck("pb") }).isTrue()
    }

    @Test fun extract_via_grid_insert_onto_an_occupied_cell_is_rejected() {
        // The GridInsert extract path treats an in-range insert index as a plain placement
        // at its cell (no reorder shift); an OCCUPIED cell is rejected. occupied_target_is_
        // rejected covers a Cell target — this pins emptyTargetReason's GridInsert branch
        // (HomeLayoutTransition.kt:366-369) for an in-range, occupied index.
        val f = folder("f", ck("pa"), ck("pb"), ck("pc"), page = 0, x = 0, y = 0)
        val other = PlacedItem(app("o", "po"), CellPos(0, 2, 0)) // li2 occupied
        val start = layout(items = listOf(f, other))
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("f"), ck("pb"), DropTarget.GridInsert(0, 2), seq("x")::next,
        )
        assertThat(r).isEqualTo(FolderEditResult.Rejected(MoveResult.Reason.TARGET_OCCUPIED_INCOMPATIBLE))
    }

    @Test fun extract_into_a_free_dock_slot_lands_the_app_in_the_dock() {
        // The rejection path (full dock) is covered above; this pins the SUCCESS path —
        // a member extracted into a non-full dock at a valid index.
        val f = folder("f", ck("pa"), ck("pb"), ck("pc"), page = 0, x = 0, y = 0)
        val x = app("x", "px")
        val start = layout(items = listOf(f), dock = listOf(x)) // dock has room (1 < 4)
        val ids = seq("extracted")
        val r = HomeLayoutTransition.removeFromFolder(start, ItemId("f"), ck("pb"), DropTarget.DockSlot(0), ids::next)
        assertThat(r).isInstanceOf(FolderEditResult.Extracted::class.java)
        val out = r.layout!!
        // pb inserted at dock index 0, ahead of x.
        assertThat(out.dock.map { (it as HomeItem.App).key }).containsExactly(ck("pb"), ck("px")).inOrder()
        assertThat(out.dock.first().id).isEqualTo(ItemId("extracted"))
        // Folder shrank but survives (still 2 members).
        val fOut = out.items.first { it.item.id == ItemId("f") }.item as HomeItem.Folder
        assertThat(fOut.members).containsExactly(ck("pa"), ck("pc")).inOrder()
    }

    @Test fun dissolve_into_a_free_dock_slot_puts_extracted_in_dock_and_survivor_on_grid() {
        // Two-member folder + a DockSlot target: extracted app lands in the dock, the
        // survivor is promoted to the folder's old grid cell.
        val f = folder("f", ck("pa"), ck("pb"), page = 0, x = 2, y = 3)
        val x = app("x", "px")
        val start = layout(items = listOf(f), dock = listOf(x))
        val ids = seq("extracted", "survivor")
        val r = HomeLayoutTransition.removeFromFolder(start, ItemId("f"), ck("pa"), DropTarget.DockSlot(1), ids::next)
        assertThat(r).isInstanceOf(FolderEditResult.FolderDissolved::class.java)
        val out = r.layout!!
        assertThat(out.items.any { it.item.id == ItemId("f") }).isFalse()
        // extracted (pa) appended into the dock at index 1.
        assertThat(out.dock.map { (it as HomeItem.App).key }).containsExactly(ck("px"), ck("pa")).inOrder()
        assertThat(out.dock.last().id).isEqualTo(ItemId("extracted"))
        // survivor (pb) at the folder's former cell.
        val survivor = out.items.first { it.pos == CellPos(0, 2, 3) }
        assertThat((survivor.item as HomeItem.App).key).isEqualTo(ck("pb"))
        assertThat(survivor.item.id).isEqualTo(ItemId("survivor"))
    }

    @Test fun extract_onto_a_brand_new_trailing_page_adds_the_page() {
        // placeNewAtTarget's Cell branch adds a page when the extract lands on the landing
        // page (pos.page == pages) (HomeLayoutTransition.kt:384). The folder sits on page 0;
        // the extracted member is dropped onto page 1 (== pages), which must be created.
        val f = folder("f", ck("pa"), ck("pb"), ck("pc"), page = 0, x = 0, y = 0)
        val start = layout(items = listOf(f), pages = 1)
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("f"), ck("pb"), DropTarget.Cell(CellPos(1, 0, 0)), seq("extracted")::next,
        )
        assertThat(r).isInstanceOf(FolderEditResult.Extracted::class.java)
        val out = r.layout!!
        assertThat(out.pages).isEqualTo(2)
        assertThat(out.items.first { it.pos == CellPos(1, 0, 0) }.item.id).isEqualTo(ItemId("extracted"))
    }

    @Test fun extract_into_an_out_of_range_dock_slot_is_rejected() {
        // emptyTargetReason's DockSlot branch rejects an index past the dock size
        // (HomeLayoutTransition.kt:375), mirroring the move-side dock_index guard — the
        // total function rejects rather than crashing on MutableList.add(idx, …). The dock
        // is NOT full, so this isolates the out-of-range term from the DOCK_FULL one.
        val f = folder("f", ck("pa"), ck("pb"), ck("pc"), page = 0, x = 0, y = 0)
        val start = layout(items = listOf(f), dock = listOf(app("x", "px"))) // dock.size = 1 (< 4)
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("f"), ck("pb"), DropTarget.DockSlot(2), seq("x")::next, // 2 > dock.size (1)
        )
        assertThat(r).isEqualTo(FolderEditResult.Rejected(MoveResult.Reason.OFF_GRID))
    }

    @Test fun removing_a_duplicated_member_is_a_noop_guard_not_a_dissolve() {
        // IHM-INV-7 forbids duplicate members, but the total function must stay defined for an
        // imported/hand-edited blob. Removing a member that appears more than once is ambiguous
        // ("which copy?"): filterNot would strip EVERY copy and wrongly dissolve the folder,
        // losing a copy. The guard collapses this structurally-impossible input to NoOp,
        // leaving the layout untouched (the use-case fires silentError in DEBUG).
        val f = folder("f", ck("pa"), ck("pb"), ck("pb"), page = 0, x = 0, y = 0)
        val start = layout(items = listOf(f))
        val r = HomeLayoutTransition.removeFromFolder(start, ItemId("f"), ck("pb"), DropTarget.Cell(CellPos(0, 1, 1)), seq("x")::next)
        assertThat(r).isEqualTo(FolderEditResult.NoOp)
        assertThat(r.layout).isNull() // nothing changed
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

    @Test fun extract_onto_the_folders_own_cell_is_rejected() {
        // B2: emptyTargetReason runs BEFORE the folder is shrunk, so the folder still
        // occupies its own cell — dropping the extracted member there hits TARGET_OCCUPIED.
        // This fragile pre-shrink ordering is exactly what structurally prevents a
        // survivor-vs-target collision; occupied_target_is_rejected uses a SEPARATE app, so
        // this pins the own-cell case specifically.
        val f = folder("f", ck("pa"), ck("pb"), ck("pc"), page = 0, x = 2, y = 1)
        val start = layout(items = listOf(f))
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("f"), ck("pb"), DropTarget.Cell(CellPos(0, 2, 1)), seq("x")::next,
        )
        assertThat(r).isEqualTo(FolderEditResult.Rejected(MoveResult.Reason.TARGET_OCCUPIED_INCOMPATIBLE))
    }

    @Test fun extract_via_grid_insert_onto_a_brand_new_trailing_page_adds_the_page() {
        // B3a: placeNewAtTarget's GridInsert branch adds a page when the resolved cell lands
        // on the landing page (pos.page == pages). extract_onto_a_brand_new_trailing_page
        // pins the Cell branch; this pins the GridInsert branch with an IN-range index.
        val f = folder("f", ck("pa"), ck("pb"), ck("pc"), page = 0, x = 0, y = 0)
        val start = layout(items = listOf(f), pages = 1)
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("f"), ck("pb"), DropTarget.GridInsert(1, 0), seq("extracted")::next,
        )
        assertThat(r).isInstanceOf(FolderEditResult.Extracted::class.java)
        val out = r.layout!!
        assertThat(out.pages).isEqualTo(2)
        assertThat(out.items.first { it.pos == CellPos(1, 0, 0) }.item.id).isEqualTo(ItemId("extracted"))
    }

    @Test fun extract_via_out_of_range_grid_insert_on_a_full_page_lands_on_a_fresh_page() {
        // B3b (new-page return): gridInsertCell falls back to firstFreeCellFrom for an
        // out-of-range index; on a home whose only page is full that returns a fresh trailing
        // page, and placeNewAtTarget adds it. extract_to_out_of_range_grid_insert_lands_at_
        // first_free lands on the SAME page — this pins the new-page path. 1×1 grid makes
        // "full" cheap (mirrors the move test's page-cap fixtures).
        val tiny = GridSpec(columns = 1, rows = 1)
        val f = HomeItem.Folder(ItemId("f"), "", listOf(ck("pa"), ck("pb"), ck("pc")))
        val start = HomeLayout(tiny, pages = 1, items = listOf(PlacedItem(f, CellPos(0, 0, 0))), dock = emptyList())
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("f"), ck("pb"), DropTarget.GridInsert(0, 5), seq("extracted")::next, // index 5 out of range
        )
        assertThat(r).isInstanceOf(FolderEditResult.Extracted::class.java)
        val out = r.layout!!
        assertThat(out.pages).isEqualTo(2)
        assertThat(out.items.first { it.pos == CellPos(1, 0, 0) }.item.id).isEqualTo(ItemId("extracted"))
    }

    @Test fun extract_via_out_of_range_grid_insert_when_every_page_is_full_is_rejected() {
        // B3b (MAX_PAGES rejection): the same fallback rejects (OFF_GRID) when
        // firstFreeCellFrom returns a page at the MAX_PAGES cap — the home is genuinely full,
        // so the extraction can't land (no app dropped, no off-cap page created). 1×1 grid,
        // folder on page 0, apps filling pages 1..MAX_PAGES-1.
        val tiny = GridSpec(columns = 1, rows = 1)
        val f = HomeItem.Folder(ItemId("f"), "", listOf(ck("pa"), ck("pb"), ck("pc")))
        val fillers = (1 until HomeLayout.MAX_PAGES).map { p -> PlacedItem(app("fill$p", "pfill$p"), CellPos(p, 0, 0)) }
        val start = HomeLayout(
            tiny,
            pages = HomeLayout.MAX_PAGES,
            items = listOf(PlacedItem(f, CellPos(0, 0, 0))) + fillers,
            dock = emptyList(),
        )
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("f"), ck("pb"), DropTarget.GridInsert(0, 5), seq("x")::next,
        )
        assertThat(r).isEqualTo(FolderEditResult.Rejected(MoveResult.Reason.OFF_GRID))
    }

    @Test fun dissolve_puts_the_survivor_at_the_dock_folders_original_non_zero_slot() {
        // B3c: placeAtPlacement's Dock branch re-inserts the survivor at the dissolved
        // folder's ORIGINAL dock index. dissolve_with_folder_in_dock uses a single-element
        // dock (index 0); here the folder sits at slot 1 behind a neighbour, pinning the
        // non-zero index shift.
        val neighbor = app("n", "pn")
        val f = HomeItem.Folder(ItemId("f"), "", listOf(ck("pa"), ck("pb")))
        val start = layout(dock = listOf(neighbor, f))
        val ids = seq("extracted", "survivor")
        val r = HomeLayoutTransition.removeFromFolder(start, ItemId("f"), ck("pa"), DropTarget.Cell(CellPos(0, 0, 0)), ids::next)
        assertThat(r).isInstanceOf(FolderEditResult.FolderDissolved::class.java)
        val out = r.layout!!
        // survivor (pb) re-inserted at the folder's old slot (index 1), behind the neighbour.
        assertThat(out.dock.map { (it as HomeItem.App).key }).containsExactly(ck("pn"), ck("pb")).inOrder()
        assertThat(out.dock[1].id).isEqualTo(ItemId("survivor"))
        // extracted (pa) at the grid target.
        assertThat(out.items.first { it.pos == CellPos(0, 0, 0) }.item.id).isEqualTo(ItemId("extracted"))
    }

    // ===================== folder → folder (scoped IHM-INV-7) ===============

    @Test fun move_member_into_another_grid_folder_shrinks_the_source() {
        // A (>=3 members) → drop pb onto grid folder B: A shrinks, B gains pb. No extraction
        // to empty space, no new ItemId minted (members are raw ComponentKeys).
        val a = folder("a", ck("pa"), ck("pb"), ck("pc"), page = 0, x = 0, y = 0)
        val b = folder("b", ck("pd"), ck("pe"), page = 0, x = 1, y = 0)
        val start = layout(items = listOf(a, b))
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("a"), ck("pb"), DropTarget.Cell(CellPos(0, 1, 0)), seq("unused")::next,
        )
        assertThat(r).isInstanceOf(FolderEditResult.MovedBetweenFolders::class.java)
        val moved = r as FolderEditResult.MovedBetweenFolders
        assertThat(moved.from).isEqualTo(ItemId("a"))
        assertThat(moved.to).isEqualTo(ItemId("b"))
        val out = r.layout
        val aOut = out.items.first { it.item.id == ItemId("a") }.item as HomeItem.Folder
        val bOut = out.items.first { it.item.id == ItemId("b") }.item as HomeItem.Folder
        assertThat(aOut.members).containsExactly(ck("pa"), ck("pc")).inOrder() // pb removed, order kept
        assertThat(bOut.members).containsExactly(ck("pd"), ck("pe"), ck("pb")).inOrder() // pb appended
    }

    @Test fun move_last_pair_member_into_another_folder_dissolves_the_source() {
        // A (exactly 2) → drop pa onto grid folder B: A dissolves, its survivor pb is promoted
        // to A's old cell, B gains pa. Exactly ONE new id (the survivor).
        val a = folder("a", ck("pa"), ck("pb"), page = 0, x = 2, y = 3)
        val b = folder("b", ck("pd"), ck("pe"), page = 0, x = 1, y = 0)
        val start = layout(items = listOf(a, b))
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("a"), ck("pa"), DropTarget.Cell(CellPos(0, 1, 0)), seq("survivor")::next,
        )
        assertThat(r).isInstanceOf(FolderEditResult.MovedBetweenFoldersDissolve::class.java)
        val moved = r as FolderEditResult.MovedBetweenFoldersDissolve
        assertThat(moved.to).isEqualTo(ItemId("b"))
        assertThat(moved.survivor).isEqualTo(ItemId("survivor"))
        val out = r.layout
        assertThat(out.items.any { it.item.id == ItemId("a") }).isFalse() // A retired
        val survivor = out.items.first { it.pos == CellPos(0, 2, 3) } // A's old cell
        assertThat((survivor.item as HomeItem.App).key).isEqualTo(ck("pb"))
        assertThat(survivor.item.id).isEqualTo(ItemId("survivor"))
        val bOut = out.items.first { it.item.id == ItemId("b") }.item as HomeItem.Folder
        assertThat(bOut.members).containsExactly(ck("pd"), ck("pe"), ck("pa")).inOrder()
    }

    @Test fun move_into_a_folder_that_already_contains_the_member_is_a_noop() {
        // B-scope uniqueness: dropping pa onto a folder B that already has pa changes nothing.
        val a = folder("a", ck("pa"), ck("pb"), ck("pc"), page = 0, x = 0, y = 0)
        val b = folder("b", ck("pa"), ck("pd"), page = 0, x = 1, y = 0)
        val start = layout(items = listOf(a, b))
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("a"), ck("pa"), DropTarget.Cell(CellPos(0, 1, 0)), seq("unused")::next,
        )
        assertThat(r).isEqualTo(FolderEditResult.NoOp)
        assertThat(r.layout).isNull()
    }

    @Test fun move_member_the_target_is_also_a_top_level_tile_still_moves_between_folders() {
        // Independent scopes: pb is ALSO a top-level tile elsewhere. Moving it A→B is
        // unaffected by the tile; the tile stays put (top-level scope is separate).
        val a = folder("a", ck("pa"), ck("pb"), ck("pc"), page = 0, x = 0, y = 0)
        val b = folder("b", ck("pd"), ck("pe"), page = 0, x = 1, y = 0)
        val tile = PlacedItem(app("tile", "pb"), CellPos(0, 3, 3))
        val start = layout(items = listOf(a, b, tile))
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("a"), ck("pb"), DropTarget.Cell(CellPos(0, 1, 0)), seq("unused")::next,
        )
        assertThat(r).isInstanceOf(FolderEditResult.MovedBetweenFolders::class.java)
        val out = r.layout!!
        // the pb tile is untouched
        val tileOut = out.items.first { it.item.id == ItemId("tile") }
        assertThat((tileOut.item as HomeItem.App).key).isEqualTo(ck("pb"))
        val bOut = out.items.first { it.item.id == ItemId("b") }.item as HomeItem.Folder
        assertThat(bOut.members).containsExactly(ck("pd"), ck("pe"), ck("pb")).inOrder()
    }

    // ===== scoped IHM-INV-7: promotion must not duplicate an existing top-level tile =====

    @Test fun extract_a_member_that_is_also_a_top_level_tile_relocates_the_tile_no_duplicate() {
        // pb is a member of A (>=3) AND already a top-level tile. Extracting pb to an empty cell
        // must NOT mint a second pb tile: the existing tile is relocated to the target (id
        // reused), A shrinks, and no new ItemId is minted.
        val a = folder("a", ck("pa"), ck("pb"), ck("pc"), page = 0, x = 0, y = 0)
        val tile = PlacedItem(app("tile", "pb"), CellPos(0, 3, 3))
        val start = layout(items = listOf(a, tile))
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("a"), ck("pb"), DropTarget.Cell(CellPos(0, 2, 2)), seq("unused")::next,
        )
        assertThat(r).isInstanceOf(FolderEditResult.Extracted::class.java)
        assertThat((r as FolderEditResult.Extracted).app).isEqualTo(ItemId("tile")) // reused, not minted
        val out = r.layout
        val pbTiles = out.items.filter { (it.item as? HomeItem.App)?.key == ck("pb") }
        assertThat(pbTiles.map { it.pos }).containsExactly(CellPos(0, 2, 2)) // exactly one, at target
        assertThat(pbTiles.single().item.id).isEqualTo(ItemId("tile"))
        val aOut = out.items.first { it.item.id == ItemId("a") }.item as HomeItem.Folder
        assertThat(aOut.members).containsExactly(ck("pa"), ck("pc")).inOrder()
    }

    @Test fun dissolve_where_the_survivor_is_also_a_top_level_tile_keeps_the_tile_no_duplicate() {
        // A=[pa,pb] (exactly 2); survivor pb is ALSO a top-level tile. Extracting pa dissolves A
        // but pb is NOT promoted a second time: the existing pb tile stays put, A's old cell is
        // left empty, and pa is the ONLY newly minted id.
        val a = folder("a", ck("pa"), ck("pb"), page = 0, x = 2, y = 3)
        val tile = PlacedItem(app("tile", "pb"), CellPos(0, 0, 5))
        val start = layout(items = listOf(a, tile))
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("a"), ck("pa"), DropTarget.Cell(CellPos(0, 1, 1)), seq("extracted")::next,
        )
        assertThat(r).isInstanceOf(FolderEditResult.FolderDissolved::class.java)
        val d = r as FolderEditResult.FolderDissolved
        assertThat(d.extracted).isEqualTo(ItemId("extracted"))
        assertThat(d.survivor).isEqualTo(ItemId("tile")) // reused existing tile, no second mint
        val out = r.layout
        assertThat(out.items.any { it.item.id == ItemId("a") }).isFalse() // A retired
        val pbTiles = out.items.filter { (it.item as? HomeItem.App)?.key == ck("pb") }
        assertThat(pbTiles.map { it.pos }).containsExactly(CellPos(0, 0, 5)) // unmoved, single
        assertThat(out.items.any { it.pos == CellPos(0, 2, 3) }).isFalse() // A's old cell empty
        val extracted = out.items.first { it.pos == CellPos(0, 1, 1) }
        assertThat((extracted.item as HomeItem.App).key).isEqualTo(ck("pa"))
        assertThat(extracted.item.id).isEqualTo(ItemId("extracted"))
    }

    @Test fun move_into_another_folder_dissolving_when_survivor_is_a_tile_no_duplicate() {
        // A=[pa,pb] dissolves into B; survivor pb is ALSO a top-level tile → not promoted again.
        // The existing tile is kept, A retired, B gains pa, and NO new id is minted.
        val a = folder("a", ck("pa"), ck("pb"), page = 0, x = 2, y = 3)
        val b = folder("b", ck("pd"), ck("pe"), page = 0, x = 1, y = 0)
        val tile = PlacedItem(app("tile", "pb"), CellPos(0, 0, 5))
        val start = layout(items = listOf(a, b, tile))
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("a"), ck("pa"), DropTarget.Cell(CellPos(0, 1, 0)), seq("unused")::next,
        )
        assertThat(r).isInstanceOf(FolderEditResult.MovedBetweenFoldersDissolve::class.java)
        assertThat((r as FolderEditResult.MovedBetweenFoldersDissolve).survivor).isEqualTo(ItemId("tile"))
        val out = r.layout
        assertThat(out.items.any { it.item.id == ItemId("a") }).isFalse()
        val pbTiles = out.items.filter { (it.item as? HomeItem.App)?.key == ck("pb") }
        assertThat(pbTiles.map { it.pos }).containsExactly(CellPos(0, 0, 5)) // unmoved, single
        val bOut = out.items.first { it.item.id == ItemId("b") }.item as HomeItem.Folder
        assertThat(bOut.members).containsExactly(ck("pd"), ck("pe"), ck("pa")).inOrder()
    }
}
