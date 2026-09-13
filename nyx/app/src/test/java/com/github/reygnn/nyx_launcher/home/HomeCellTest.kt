package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for the dense row-major renderers [HomeLayout.pageCells] and
 * [HomeLayout.dockCells] — the mapping from the positioned model to the flat cell
 * lists the adapters render. Both were untested before this file; the gap→Empty
 * index arithmetic and the Folder→[HomeCell.Folder] path are the interesting bits.
 */
class HomeCellTest {

    private val grid = GridSpec(columns = 2, rows = 2) // 4 cells/page, easy to enumerate
    private fun ck(p: String) = ComponentKey(p, "$p.Main")
    private fun app(id: String, p: String = id) = HomeItem.App(ItemId(id), ck(p))
    private fun folder(id: String, vararg m: ComponentKey) = HomeItem.Folder(ItemId(id), "", m.toList())
    private fun placed(item: HomeItem, x: Int, y: Int, page: Int = 0) = PlacedItem(item, CellPos(page, x, y))
    private fun layout(items: List<PlacedItem> = emptyList(), dock: List<HomeItem> = emptyList()) =
        HomeLayout(grid, pages = 1, items = items, dock = dock)

    @Test fun maps_apps_and_folders_into_the_right_cells_with_empties_for_gaps() {
        val a = app("a", "pa")
        val f = folder("f", ck("pb"), ck("pc"))
        // a at li0 (0,0); folder at li3 (1,1); li1 and li2 are gaps.
        val cells = layout(items = listOf(placed(a, 0, 0), placed(f, 1, 1))).pageCells(0)
        assertThat(cells).hasSize(4)
        assertThat(cells[0]).isEqualTo(HomeCell.App(a.id, ck("pa")))
        assertThat(cells[1]).isEqualTo(HomeCell.Empty)
        assertThat(cells[2]).isEqualTo(HomeCell.Empty)
        assertThat(cells[3]).isEqualTo(HomeCell.Folder(f.id, listOf(ck("pb"), ck("pc"))))
    }

    @Test fun index_is_row_major_y_times_columns_plus_x() {
        // Item at (x=1, y=0) must land at linear index 1, not 2.
        val a = app("a", "pa")
        val cells = layout(items = listOf(placed(a, 1, 0))).pageCells(0)
        assertThat(cells[1]).isEqualTo(HomeCell.App(a.id, ck("pa")))
        assertThat(cells[2]).isEqualTo(HomeCell.Empty)
    }

    @Test fun an_empty_page_is_all_empty_cells() {
        assertThat(layout().pageCells(0)).containsExactly(
            HomeCell.Empty, HomeCell.Empty, HomeCell.Empty, HomeCell.Empty,
        ).inOrder()
    }

    @Test fun an_out_of_range_page_yields_a_full_grid_of_empties_not_a_crash() {
        val a = app("a", "pa")
        val cells = layout(items = listOf(placed(a, 0, 0, page = 0))).pageCells(5)
        assertThat(cells).hasSize(4)
        assertThat(cells.all { it == HomeCell.Empty }).isTrue()
    }

    @Test fun only_the_requested_page_is_rendered() {
        val a = app("a", "pa")
        val b = app("b", "pb")
        val start = layout(items = listOf(placed(a, 0, 0, page = 0), placed(b, 0, 0, page = 1)))
        assertThat(start.pageCells(1)[0]).isEqualTo(HomeCell.App(b.id, ck("pb")))
        assertThat(start.pageCells(0)[0]).isEqualTo(HomeCell.App(a.id, ck("pa")))
    }

    @Test fun dock_cells_map_apps_and_folders_in_order_without_empties() {
        val a = app("a", "pa")
        val f = folder("f", ck("pb"), ck("pc"))
        val cells = layout(dock = listOf(a, f)).dockCells()
        assertThat(cells).containsExactly(
            HomeCell.App(a.id, ck("pa")),
            HomeCell.Folder(f.id, listOf(ck("pb"), ck("pc"))),
        ).inOrder()
    }

    @Test fun empty_dock_has_no_cells() {
        assertThat(layout().dockCells()).isEmpty()
    }

    // ---- pageCells under an invariant violation (off-grid / collision) ----
    // On-grid, no-collision are transition + regridder invariants (HomeLayout KDoc: a
    // violation is a programmer error, NOT a user outcome), so pageCells assumes
    // 0 <= x < columns, 0 <= y < rows and one item per cell. An imported/restored/hand-
    // edited blob is saved verbatim with no coordinate clamp, so it CAN slip one in.
    // pageCells now filters out-of-bounds items BEFORE indexing so such an item can never
    // alias onto a valid neighbour cell; a same-cell collision still resolves last-wins.

    @Test fun an_off_grid_column_item_is_ignored_not_aliased_onto_a_valid_cell() {
        // Regression for the aliasing bug: x == columns folds y*cols+x onto the SAME linear
        // index as (x=0, y+1) — (x=2, y=0) on a 2-wide grid would land on (x=0, y=1)'s slot.
        // The bounds filter drops it instead, so the neighbour cell stays Empty.
        val b = app("b", "pb")
        val cells = layout(items = listOf(placed(b, x = 2, y = 0))).pageCells(0)
        assertThat(cells).hasSize(4)
        assertThat(cells.all { it == HomeCell.Empty }).isTrue() // off-grid item ignored, no alias
    }

    @Test fun an_item_below_the_grid_is_ignored() {
        // y == rows is out of bounds → filtered out (previously fell out of the index range
        // anyway; now it is dropped explicitly by the same guard).
        val b = app("b", "pb")
        val cells = layout(items = listOf(placed(b, x = 0, y = 2))).pageCells(0)
        assertThat(cells).hasSize(4)
        assertThat(cells.all { it == HomeCell.Empty }).isTrue()
    }

    @Test fun a_negative_coordinate_item_is_ignored() {
        // The bounds filter's lower end: a negative x (or y) is out of bounds too and must
        // not produce a negative index or otherwise leak into the render.
        val b = app("b", "pb")
        val cells = layout(items = listOf(placed(b, x = -1, y = 0))).pageCells(0)
        assertThat(cells.all { it == HomeCell.Empty }).isTrue()
    }

    @Test fun two_items_sharing_a_cell_render_only_the_last() {
        // Both are in-bounds, so the bounds filter leaves them; associateBy keeps the LAST in
        // list order. Pinned so the last-wins resolution stays deliberate (the guard above
        // does NOT dedup a genuine same-cell collision — that remains a transition invariant).
        val a = app("a", "pa")
        val b = app("b", "pb")
        val cells = layout(items = listOf(placed(a, 0, 0), placed(b, 0, 0))).pageCells(0) // both li0
        assertThat(cells[0]).isEqualTo(HomeCell.App(b.id, ck("pb"))) // last in list wins
    }
}
