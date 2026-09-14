package com.github.reygnn.nyx_launcher.home.transition

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.model.RegridOutcome
import com.github.reygnn.nyx_launcher.home.model.Span
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure JVM tests for the device-grid re-fit policy (ICON_HOME_MODEL_SPEC §10). */
class HomeLayoutRegridderTest {

    private fun ck(p: String) = ComponentKey(p, "$p.Main")
    private fun app(id: String, p: String) = HomeItem.App(ItemId(id), ck(p))
    private fun placed(item: HomeItem, page: Int, x: Int, y: Int) = PlacedItem(item, CellPos(page, x, y))
    private fun layout(grid: GridSpec, items: List<PlacedItem> = emptyList(), dock: List<HomeItem> = emptyList(), pages: Int = 1) =
        HomeLayout(grid, pages, items, dock)

    @Test fun same_grid_is_unchanged() {
        val g = GridSpec(4, 6)
        val start = layout(g, items = listOf(placed(app("a", "pa"), 0, 1, 1)))
        assertThat(HomeLayoutRegridder.fit(start, g)).isEqualTo(RegridOutcome.Unchanged)
    }

    @Test fun growing_keeps_positions_and_updates_grid() {
        val start = layout(GridSpec(4, 6), items = listOf(placed(app("a", "pa"), 0, 3, 5)))
        val out = HomeLayoutRegridder.fit(start, GridSpec(6, 8)) as RegridOutcome.Changed
        assertThat(out.layout.grid).isEqualTo(GridSpec(6, 8))
        // Everything was already in bounds → nothing moves.
        assertThat(out.layout.items.single().pos).isEqualTo(CellPos(0, 3, 5))
    }

    @Test fun shrinking_relocates_off_grid_items_without_loss_or_collision() {
        val start = layout(
            GridSpec(6, 8),
            items = listOf(placed(app("b", "pb"), 0, 0, 0), placed(app("a", "pa"), 0, 5, 0)),
        )
        val out = HomeLayoutRegridder.fit(start, GridSpec(4, 6)) as RegridOutcome.Changed
        // B stays (in bounds); A (x=5, off-grid) relocates to the first free cell.
        val byId = out.layout.items.associate { it.item.id to it.pos }
        assertThat(byId[ItemId("b")]).isEqualTo(CellPos(0, 0, 0))
        assertThat(byId[ItemId("a")]).isEqualTo(CellPos(0, 1, 0))
        assertThat(out.layout.items.map { it.item.id }).containsExactly(ItemId("a"), ItemId("b"))
        // No two items share a cell.
        assertThat(out.layout.items.map { it.pos }.toSet()).hasSize(2)
    }

    @Test fun dock_overflow_is_rehomed_onto_grid_not_dropped() {
        val start = layout(
            GridSpec(5, 6),
            dock = listOf(app("d0", "p0"), app("d1", "p1"), app("d2", "p2"), app("d3", "p3"), app("d4", "p4")),
        )
        val out = HomeLayoutRegridder.fit(start, GridSpec(4, 6)) as RegridOutcome.Changed
        assertThat(out.layout.dock.map { it.id }).containsExactly(
            ItemId("d0"), ItemId("d1"), ItemId("d2"), ItemId("d3"),
        ).inOrder()
        // The 5th dock item is placed on the grid, not lost.
        assertThat(out.layout.items.single().item.id).isEqualTo(ItemId("d4"))
        assertThat(out.layout.items.single().pos).isEqualTo(CellPos(0, 0, 0))
    }

    @Test fun relocation_overflows_to_a_new_page_when_first_page_is_full() {
        // 2×1 grid = 2 cells/page; one in-bounds occupant + two off-grid items.
        val start = layout(
            GridSpec(3, 2),
            items = listOf(
                placed(app("keep", "pk"), 0, 0, 0),
                placed(app("x", "px"), 0, 2, 1), // off-grid under 2×1
                placed(app("y", "py"), 0, 1, 1), // off-grid under 2×1
            ),
        )
        val out = HomeLayoutRegridder.fit(start, GridSpec(2, 1)) as RegridOutcome.Changed
        val byId = out.layout.items.associate { it.item.id to it.pos }
        assertThat(byId[ItemId("keep")]).isEqualTo(CellPos(0, 0, 0))
        // Off-grid items relocate in (page,y,x) order: y before x, so (0,1,0) then page 1.
        assertThat(byId[ItemId("y")]).isEqualTo(CellPos(0, 1, 0))
        assertThat(byId[ItemId("x")]).isEqualTo(CellPos(1, 0, 0))
        assertThat(out.layout.pages).isEqualTo(2)
        assertThat(out.layout.items).hasSize(3) // nothing lost
    }

    @Test fun over_capacity_dock_on_the_same_grid_rehomes_the_overflow() {
        // A first-run seed can put more apps in the dock than the measured grid is
        // wide; fitting to the SAME grid must still re-home the overflow (not no-op).
        val g = GridSpec(4, 6)
        val start = layout(
            g,
            dock = listOf(app("d0", "p0"), app("d1", "p1"), app("d2", "p2"), app("d3", "p3"), app("d4", "p4")),
        )
        val out = HomeLayoutRegridder.fit(start, g) as RegridOutcome.Changed
        assertThat(out.layout.dock.map { it.id }).containsExactly(
            ItemId("d0"), ItemId("d1"), ItemId("d2"), ItemId("d3"),
        ).inOrder()
        assertThat(out.layout.items.single().item.id).isEqualTo(ItemId("d4"))
        assertThat(out.layout.items.single().pos).isEqualTo(CellPos(0, 0, 0))
    }

    private fun folder(id: String, vararg m: ComponentKey) = HomeItem.Folder(ItemId(id), "", m.toList())

    @Test fun a_folder_in_the_dock_overflow_is_rehomed_onto_the_grid_not_dropped() {
        // The overflow queue is HomeItem-typed, so a dock FOLDER (not just apps) that
        // spills past dock capacity is re-homed onto the grid with a default span.
        val g = GridSpec(4, 6)
        val start = layout(
            g,
            dock = listOf(
                app("d0", "p0"), app("d1", "p1"), app("d2", "p2"), app("d3", "p3"),
                folder("fd", ck("pa"), ck("pb")), // 5th item → overflow
            ),
        )
        val out = HomeLayoutRegridder.fit(start, g) as RegridOutcome.Changed
        assertThat(out.layout.dock.map { it.id }).containsExactly(
            ItemId("d0"), ItemId("d1"), ItemId("d2"), ItemId("d3"),
        ).inOrder()
        val rehomed = out.layout.items.single()
        assertThat(rehomed.item.id).isEqualTo(ItemId("fd"))
        assertThat((rehomed.item as HomeItem.Folder).members).containsExactly(ck("pa"), ck("pb")).inOrder()
        assertThat(rehomed.pos).isEqualTo(CellPos(0, 0, 0))
        assertThat(rehomed.span).isEqualTo(Span())
    }

    @Test fun a_grid_change_recomputes_pages_and_drops_stale_trailing_pages() {
        // pages says 5 but only page 0 is occupied; any grid change recomputes pages
        // purely from item positions, so the stale trailing pages fall away.
        val start = layout(GridSpec(4, 6), items = listOf(placed(app("a", "pa"), 0, 0, 0)), pages = 5)
        val out = HomeLayoutRegridder.fit(start, GridSpec(5, 6)) as RegridOutcome.Changed
        assertThat(out.layout.grid).isEqualTo(GridSpec(5, 6))
        assertThat(out.layout.pages).isEqualTo(1)
    }

    @Test fun a_dock_exactly_at_capacity_on_the_same_grid_is_unchanged() {
        // Boundary of the persist-storm guard (dock.size <= columns): exactly `columns`
        // dock items on the matching grid must be a no-op, not a needless re-home/save.
        val g = GridSpec(4, 6)
        val start = layout(g, dock = (0 until 4).map { app("d$it", "p$it") }) // size == columns
        assertThat(HomeLayoutRegridder.fit(start, g)).isEqualTo(RegridOutcome.Unchanged)
    }

    @Test fun an_empty_layout_grows_to_the_new_grid_with_a_single_page() {
        val start = layout(GridSpec(4, 6)) // no items, empty dock
        val out = HomeLayoutRegridder.fit(start, GridSpec(5, 8)) as RegridOutcome.Changed
        assertThat(out.layout.grid).isEqualTo(GridSpec(5, 8))
        assertThat(out.layout.items).isEmpty()
        assertThat(out.layout.pages).isEqualTo(1)
    }

    @Test fun an_empty_layout_on_the_same_grid_is_unchanged() {
        val g = GridSpec(4, 6)
        assertThat(HomeLayoutRegridder.fit(layout(g), g)).isEqualTo(RegridOutcome.Unchanged)
    }

    @Test fun off_grid_items_relocate_before_dock_overflow() {
        // Both relocation queues are non-empty at once: the ordering guarantee
        // (off-grid grid items in page/y/x order FIRST, then dock overflow) is only
        // tested in isolation elsewhere. Shrinking 6×8 → 4×6 pushes one grid item
        // off-grid AND overflows the dock; the off-grid item must take the earlier
        // free cell.
        val start = layout(
            GridSpec(6, 8),
            items = listOf(placed(app("keep", "pk"), 0, 0, 0), placed(app("off", "poff"), 0, 5, 0)),
            dock = (0 until 6).map { app("d$it", "p$it") }, // 6 > target columns (4)
        )
        val out = HomeLayoutRegridder.fit(start, GridSpec(4, 6)) as RegridOutcome.Changed
        // Dock trimmed to capacity, order preserved.
        assertThat(out.layout.dock.map { it.id }).containsExactly(
            ItemId("d0"), ItemId("d1"), ItemId("d2"), ItemId("d3"),
        ).inOrder()
        val byId = out.layout.items.associate { it.item.id to it.pos }
        assertThat(byId[ItemId("keep")]).isEqualTo(CellPos(0, 0, 0)) // in bounds, unmoved
        assertThat(byId[ItemId("off")]).isEqualTo(CellPos(0, 1, 0)) // off-grid item first
        assertThat(byId[ItemId("d4")]).isEqualTo(CellPos(0, 2, 0)) // then dock overflow…
        assertThat(byId[ItemId("d5")]).isEqualTo(CellPos(0, 3, 0)) // …in dock order
        assertThat(out.layout.items).hasSize(4) // nothing lost
    }

    @Test fun off_grid_items_relocate_in_page_order_across_pages() {
        // The off-grid queue is sorted by (page, y, x), but every other multi-off-grid test
        // keeps its off-grid items on page 0, so the PAGE key of the sort is never exercised.
        // Two off-grid items on different pages, listed page-1-FIRST, must still relocate in
        // page order: the page-0 item takes the earlier free cell. Without the page sort the
        // input order would place the page-1 item first, so this discriminates the key.
        val onPage1 = placed(app("b", "pb"), 1, 5, 0) // x=5 off-grid under 4 cols, page 1
        val onPage0 = placed(app("a", "pa"), 0, 5, 0) // x=5 off-grid under 4 cols, page 0
        val start = layout(GridSpec(6, 8), items = listOf(onPage1, onPage0), pages = 2) // page-1 first
        val out = HomeLayoutRegridder.fit(start, GridSpec(4, 6)) as RegridOutcome.Changed
        val byId = out.layout.items.associate { it.item.id to it.pos }
        assertThat(byId[ItemId("a")]).isEqualTo(CellPos(0, 0, 0)) // page-0 item placed first
        assertThat(byId[ItemId("b")]).isEqualTo(CellPos(0, 1, 0)) // page-1 item second
        assertThat(out.layout.pages).isEqualTo(1) // both landed on page 0
    }

    @Test fun a_relocated_off_grid_items_span_is_preserved() {
        // The regridder promises "Span is preserved" for relocated off-grid grid items
        // (HomeLayoutRegridder.kt:59). v1 never sets a span > 1×1, so this guards the v2
        // widget lift: an off-grid item carrying a non-default span keeps it after the
        // re-fit, while dock overflow gets the default span.
        val wide = PlacedItem(app("wide", "pw"), CellPos(0, 5, 0), Span(2, 2)) // off-grid under 4×6
        val start = layout(
            GridSpec(6, 8),
            items = listOf(wide),
        )
        val out = HomeLayoutRegridder.fit(start, GridSpec(4, 6)) as RegridOutcome.Changed
        val relocated = out.layout.items.single { it.item.id == ItemId("wide") }
        assertThat(relocated.pos).isEqualTo(CellPos(0, 0, 0)) // moved onto the new grid
        assertThat(relocated.span).isEqualTo(Span(2, 2)) // span survives the relocation
    }

    @Test fun regrid_is_idempotent() {
        val start = layout(GridSpec(6, 8), items = listOf(placed(app("a", "pa"), 0, 5, 7)))
        val first = HomeLayoutRegridder.fit(start, GridSpec(4, 6)) as RegridOutcome.Changed
        val second = HomeLayoutRegridder.fit(first.layout, GridSpec(4, 6))
        assertThat(second).isEqualTo(RegridOutcome.Unchanged)
    }

    // ---- Page cap (HomeLayout.MAX_PAGES): the regridder must never emit a page the
    // pager can't render, or the app lands on an unreachable page. Overflow is dropped
    // from the layout, which is not "lost": the drawer lists every installed app. ----

    @Test fun relocation_is_capped_at_max_pages_and_overflow_is_dropped() {
        // Target 1×1 = one cell per page → the cap allows exactly MAX_PAGES occupants.
        // Twelve off-grid items (x ≥ 1 under a single column) queue up in x order; the
        // first MAX_PAGES fill pages 0..MAX_PAGES-1, the rest fall off the layout.
        val ids = (1..12).map { "i%02d".format(it) }
        val start = layout(
            GridSpec(20, 1),
            items = ids.mapIndexed { i, id -> placed(app(id, "p$id"), 0, i + 1, 0) },
        )
        val out = HomeLayoutRegridder.fit(start, GridSpec(1, 1)) as RegridOutcome.Changed
        // Exactly MAX_PAGES items survive, one per page, none past the cap.
        assertThat(out.layout.items).hasSize(HomeLayout.MAX_PAGES)
        assertThat(out.layout.pages).isEqualTo(HomeLayout.MAX_PAGES)
        assertThat(out.layout.items.all { it.pos.page in 0 until HomeLayout.MAX_PAGES }).isTrue()
        val survivors = out.layout.items.map { it.item.id.raw }.toSet()
        assertThat(survivors).containsExactlyElementsIn(ids.take(HomeLayout.MAX_PAGES))
        // The trailing three (i10..i12) are dropped — reachable via the drawer, not lost.
        assertThat(survivors).containsNoneIn(ids.drop(HomeLayout.MAX_PAGES))
    }

    @Test fun an_in_bounds_item_past_the_page_cap_is_pulled_back_onto_a_reachable_page() {
        // A stale/pre-cap layout with an item spatially in bounds but on page 12: a grid
        // change must NOT keep it there (the pager never renders page 12) — it is treated
        // as off-grid and relocated onto the first reachable cell.
        val start = layout(GridSpec(5, 6), items = listOf(placed(app("stale", "ps"), 12, 0, 0)))
        val out = HomeLayoutRegridder.fit(start, GridSpec(4, 6)) as RegridOutcome.Changed
        assertThat(out.layout.items.single().pos).isEqualTo(CellPos(0, 0, 0))
        assertThat(out.layout.pages).isEqualTo(1)
    }

    @Test fun a_stale_over_cap_item_on_the_matching_grid_is_still_pulled_back_not_a_no_op() {
        // The persist-storm guard short-circuits on a matching grid, but an item past the
        // page cap must override that — otherwise it would stay unreachable forever. The
        // spatial coordinates are in bounds; only the page is out of range.
        val g = GridSpec(4, 6)
        val start = layout(g, items = listOf(placed(app("stale", "ps"), 10, 2, 3)))
        val out = HomeLayoutRegridder.fit(start, g) as RegridOutcome.Changed
        assertThat(out.layout.items.single().pos).isEqualTo(CellPos(0, 0, 0))
        assertThat(out.layout.pages).isEqualTo(1)
    }

    @Test fun a_spatially_off_grid_item_on_the_matching_grid_is_pulled_back_not_a_no_op() {
        // Spatial counterpart to a_stale_over_cap_item_on_the_matching_grid…: the persist-
        // storm guard short-circuits on a matching grid, but an item whose x (or y) sits
        // outside the grid bounds must still override that. The regridder never emits such
        // an item, but an imported/restored/hand-edited blob is saved verbatim with no
        // coordinate clamp (ImportLayoutUseCase, NyxBackupManager) — so a blob whose stored
        // grid equals the measured device grid can carry one. Left in place it would be
        // mis-rendered (pageCells aliases y*cols+x onto a foreign cell or drops it); it must
        // be relocated onto a reachable cell instead. Page and dock are both fine here, so
        // only the spatial term of the guard can catch it.
        val g = GridSpec(4, 6)
        val start = layout(g, items = listOf(placed(app("wide", "pw"), 0, 5, 0))) // x=5 ≥ columns 4
        val out = HomeLayoutRegridder.fit(start, g) as RegridOutcome.Changed
        assertThat(out.layout.items.single().pos).isEqualTo(CellPos(0, 0, 0))
        assertThat(out.layout.pages).isEqualTo(1)
    }

    @Test fun a_y_off_grid_item_on_the_matching_grid_is_also_pulled_back() {
        // The guard's y term, symmetric to the x case above: a valid column but a row past
        // the grid height on an otherwise-matching grid is off-grid too and is relocated.
        val g = GridSpec(4, 6)
        val start = layout(g, items = listOf(placed(app("tall", "pt"), 0, 0, 6))) // y=6 ≥ rows 6
        val out = HomeLayoutRegridder.fit(start, g) as RegridOutcome.Changed
        assertThat(out.layout.items.single().pos).isEqualTo(CellPos(0, 0, 0))
        assertThat(out.layout.pages).isEqualTo(1)
    }

    // ---- Placement details: hole-filling, cross-page collision, span, idempotency ----

    @Test fun relocated_items_fill_in_bounds_holes_before_appending() {
        // In-bounds items leave (0,0,0) free (they sit at (1,0) and (0,1)); an off-grid
        // item must take that first free cell, not append after the occupied ones.
        val start = layout(
            GridSpec(6, 8),
            items = listOf(
                placed(app("h1", "ph1"), 0, 1, 0), // in bounds under 4×6
                placed(app("h2", "ph2"), 0, 0, 1), // in bounds under 4×6
                placed(app("off", "poff"), 0, 5, 0), // off-grid under 4×6
            ),
        )
        val out = HomeLayoutRegridder.fit(start, GridSpec(4, 6)) as RegridOutcome.Changed
        val byId = out.layout.items.associate { it.item.id to it.pos }
        assertThat(byId[ItemId("h1")]).isEqualTo(CellPos(0, 1, 0)) // unmoved
        assertThat(byId[ItemId("h2")]).isEqualTo(CellPos(0, 0, 1)) // unmoved
        assertThat(byId[ItemId("off")]).isEqualTo(CellPos(0, 0, 0)) // filled the hole
    }

    @Test fun relocation_skips_a_cell_an_in_bounds_item_holds_on_a_later_page() {
        // Cross-page collision avoidance: `occupied` is seeded with in-bounds items on
        // ALL pages, so a relocation scanning row-major must jump over an in-bounds
        // occupant sitting on a later page. Target 1×1 = one cell per page.
        val start = layout(
            GridSpec(5, 5),
            items = listOf(
                placed(app("keep", "pk"), 2, 0, 0), // in bounds (page 2, cell 0,0)
                placed(app("o1", "po1"), 0, 1, 0), // off-grid under 1×1
                placed(app("o2", "po2"), 0, 2, 0), // off-grid
                placed(app("o3", "po3"), 0, 3, 0), // off-grid
            ),
        )
        val out = HomeLayoutRegridder.fit(start, GridSpec(1, 1)) as RegridOutcome.Changed
        val byId = out.layout.items.associate { it.item.id to it.pos }
        assertThat(byId[ItemId("keep")]).isEqualTo(CellPos(2, 0, 0)) // unmoved
        assertThat(byId[ItemId("o1")]).isEqualTo(CellPos(0, 0, 0))
        assertThat(byId[ItemId("o2")]).isEqualTo(CellPos(1, 0, 0))
        assertThat(byId[ItemId("o3")]).isEqualTo(CellPos(3, 0, 0)) // skipped page 2 (keep)
        assertThat(out.layout.pages).isEqualTo(4)
    }

    @Test fun an_off_grid_folder_is_relocated_with_members_and_span_preserved() {
        // Counterpart to the dock-overflow folder case: a FOLDER sitting off-grid (not in
        // the dock) is relocated onto the new grid keeping its members and its span.
        val f = PlacedItem(folder("fg", ck("pa"), ck("pb")), CellPos(0, 5, 0), Span(2, 2))
        val start = layout(GridSpec(6, 8), items = listOf(f))
        val out = HomeLayoutRegridder.fit(start, GridSpec(4, 6)) as RegridOutcome.Changed
        val relocated = out.layout.items.single()
        assertThat(relocated.item.id).isEqualTo(ItemId("fg"))
        assertThat((relocated.item as HomeItem.Folder).members).containsExactly(ck("pa"), ck("pb")).inOrder()
        assertThat(relocated.pos).isEqualTo(CellPos(0, 0, 0))
        assertThat(relocated.span).isEqualTo(Span(2, 2))
    }

    @Test fun regrid_is_idempotent_after_a_multi_page_shrink() {
        // The single-item idempotency test doesn't exercise a result that spilled onto a
        // second page. Pack a page full so the shrink produces two pages, then re-fit to
        // the same target: everything is now in bounds → Unchanged.
        val eight = (0 until 8).map { i -> placed(app("a$i", "pa$i"), 0, i % 4, i / 4) } // 4×2 packed
        val start = layout(GridSpec(4, 2), items = eight)
        val first = HomeLayoutRegridder.fit(start, GridSpec(2, 2)) as RegridOutcome.Changed
        assertThat(first.layout.pages).isEqualTo(2) // spilled onto a second page
        val second = HomeLayoutRegridder.fit(first.layout, GridSpec(2, 2))
        assertThat(second).isEqualTo(RegridOutcome.Unchanged)
    }

    // ---- Same-cell collision healing (imported/hand-edited blob) ----
    // Off-grid coordinates are pulled back (above); a same-cell collision of two DISTINCT
    // items must be healed the same way. Left in place the loser stays hidden under the
    // winner forever — pageCells renders last-wins, reconcile dedups only by ComponentKey,
    // and (before this fix) the regridder classified BOTH as in-bounds and moved neither.

    @Test fun a_same_cell_collision_on_the_matching_grid_relocates_the_later_item() {
        // Both items are spatially in bounds and the grid matches, so only the collision term
        // of the no-op guard can catch this. The first item in list order keeps the cell; the
        // later one is relocated to the first free cell — both end up visible and reachable.
        val g = GridSpec(4, 6)
        val start = layout(
            g,
            items = listOf(placed(app("first", "pf"), 0, 0, 0), placed(app("dup", "pd"), 0, 0, 0)),
        )
        val out = HomeLayoutRegridder.fit(start, g) as RegridOutcome.Changed // NOT a no-op
        val byId = out.layout.items.associate { it.item.id to it.pos }
        assertThat(byId[ItemId("first")]).isEqualTo(CellPos(0, 0, 0)) // first claimant keeps the cell
        assertThat(byId[ItemId("dup")]).isEqualTo(CellPos(0, 1, 0)) // later one relocated to first free
        assertThat(out.layout.items.map { it.pos }.toSet()).hasSize(2) // no two share a cell now
    }

    @Test fun collision_healing_preserves_a_folder_collider_with_its_members() {
        // The relocated collider can be a FOLDER — it keeps its members and span, it is not
        // dropped or flattened. Here the folder is the later (losing) item at the shared cell.
        val g = GridSpec(4, 6)
        val keeper = placed(app("keep", "pk"), 0, 2, 3)
        val f = PlacedItem(folder("fd", ck("pa"), ck("pb")), CellPos(0, 2, 3), Span(2, 2)) // same cell
        val out = HomeLayoutRegridder.fit(layout(g, items = listOf(keeper, f)), g) as RegridOutcome.Changed
        assertThat(out.layout.items.first { it.item.id == ItemId("keep") }.pos).isEqualTo(CellPos(0, 2, 3))
        val rehomed = out.layout.items.first { it.item.id == ItemId("fd") }
        assertThat((rehomed.item as HomeItem.Folder).members).containsExactly(ck("pa"), ck("pb")).inOrder()
        assertThat(rehomed.span).isEqualTo(Span(2, 2)) // span survives
        assertThat(rehomed.pos).isNotEqualTo(CellPos(0, 2, 3)) // moved off the shared cell
    }

    @Test fun collision_healing_is_idempotent() {
        // Once healed, the positions are all distinct → a re-fit to the same grid is a no-op.
        val g = GridSpec(4, 6)
        val start = layout(
            g,
            items = listOf(placed(app("first", "pf"), 0, 0, 0), placed(app("dup", "pd"), 0, 0, 0)),
        )
        val healed = HomeLayoutRegridder.fit(start, g) as RegridOutcome.Changed
        assertThat(HomeLayoutRegridder.fit(healed.layout, g)).isEqualTo(RegridOutcome.Unchanged)
    }

    @Test fun a_folder_that_only_fits_past_the_page_cap_is_dropped() {
        // Counterpart to relocation_is_capped_at_max_pages_…: the dropped overflow item can be
        // a FOLDER, not just an app. Every MAX_PAGES cell of a 1×1 grid is filled by an app; an
        // off-grid folder then has nowhere within the cap and is dropped from the layout — its
        // grouping is lost, but the member apps remain reachable via the drawer. Pins the KDoc
        // claim about folders, which was previously untested.
        val tiny = GridSpec(1, 1)
        val fillers = (0 until HomeLayout.MAX_PAGES).map { p -> placed(app("f$p", "pf$p"), p, 0, 0) }
        val overflowFolder = PlacedItem(folder("fd", ck("pa"), ck("pb")), CellPos(0, 5, 0)) // off-grid under 1 col
        val out = HomeLayoutRegridder.fit(layout(tiny, items = fillers + overflowFolder), tiny) as RegridOutcome.Changed
        assertThat(out.layout.items.any { it.item.id == ItemId("fd") }).isFalse() // folder dropped
        assertThat(out.layout.items).hasSize(HomeLayout.MAX_PAGES) // only the in-bounds apps survive
    }
}
