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

    @Test fun regrid_is_idempotent() {
        val start = layout(GridSpec(6, 8), items = listOf(placed(app("a", "pa"), 0, 5, 7)))
        val first = HomeLayoutRegridder.fit(start, GridSpec(4, 6)) as RegridOutcome.Changed
        val second = HomeLayoutRegridder.fit(first.layout, GridSpec(4, 6))
        assertThat(second).isEqualTo(RegridOutcome.Unchanged)
    }
}
