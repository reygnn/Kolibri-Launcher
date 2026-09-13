package com.github.reygnn.nyx_launcher.home.model

import com.github.reygnn.launcher.core.ComponentKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for [HomeLayout.firstFreeCell] — the row-major allocator the
 * transitions and MainActivity lean on for "where does a new item land". Every
 * branch (gap-fill, page rollover, all-full trailing page, empty layout) is pinned
 * here; before this file it had zero direct coverage.
 */
class HomeLayoutQueriesTest {

    private val grid = GridSpec(columns = 4, rows = 6) // 24 cells/page
    private fun ck(p: String) = ComponentKey(p, "$p.Main")
    private fun app(id: String, p: String = id) = HomeItem.App(ItemId(id), ck(p))

    /** Place a filler app at linear reading-order index [li] on [page]. */
    private fun at(li: Int, page: Int = 0) =
        PlacedItem(app("i$page-$li", "p$page-$li"), CellPos(page, li % grid.columns, li / grid.columns))

    private fun layout(items: List<PlacedItem> = emptyList(), pages: Int = 1) =
        HomeLayout(grid, pages, items, dock = emptyList())

    @Test fun empty_layout_returns_the_first_cell() {
        assertThat(layout().firstFreeCell()).isEqualTo(CellPos(0, 0, 0))
    }

    @Test fun fills_the_first_row_major_gap() {
        // li0, li1, li3 occupied → the gap at li2 (x=2, y=0) is first free.
        val start = layout(items = listOf(at(0), at(1), at(3)))
        assertThat(start.firstFreeCell()).isEqualTo(CellPos(0, 2, 0))
    }

    @Test fun reconstructs_x_and_y_from_a_gap_on_a_later_row() {
        // Fill li0..4 and li6, leaving li5 as the only gap → li5 = x1,y1.
        val filled = (listOf(0, 1, 2, 3, 4, 6)).map { at(it) }
        assertThat(layout(items = filled).firstFreeCell()).isEqualTo(CellPos(0, 1, 1))
    }

    @Test fun rolls_over_to_the_next_page_when_page_0_is_full() {
        // Page 0 completely full (li0..23); page 1 empty → first free is page 1 li0.
        val page0Full = (0 until 24).map { at(it, page = 0) }
        val start = layout(items = page0Full, pages = 2)
        assertThat(start.firstFreeCell()).isEqualTo(CellPos(1, 0, 0))
    }

    @Test fun finds_a_gap_on_a_later_page_before_appending() {
        // Page 0 full, page 1 has only li0 occupied → first free is page 1 li1.
        val items = (0 until 24).map { at(it, page = 0) } + at(0, page = 1)
        assertThat(layout(items = items, pages = 2).firstFreeCell()).isEqualTo(CellPos(1, 1, 0))
    }

    @Test fun all_pages_full_returns_a_fresh_trailing_page() {
        // Both pages full → CellPos(pages, 0, 0): the append contract the transitions rely on.
        val items = (0 until 24).map { at(it, page = 0) } + (0 until 24).map { at(it, page = 1) }
        assertThat(layout(items = items, pages = 2).firstFreeCell()).isEqualTo(CellPos(2, 0, 0))
    }

    @Test fun zero_pages_returns_the_first_cell_of_page_zero() {
        // A degenerate empty layout (pages == 0): the scan loop never runs and it
        // returns CellPos(0, 0, 0), the start of a first page.
        assertThat(layout(pages = 0).firstFreeCell()).isEqualTo(CellPos(0, 0, 0))
    }

    @Test fun all_max_pages_full_returns_an_off_cap_trailing_page() {
        // Every one of the MAX_PAGES pages is full. firstFreeCell is deliberately
        // UNCAPPED: it returns CellPos(MAX_PAGES, 0, 0), a page index past the cap,
        // rather than signalling "no room". Safety rests entirely on the caller
        // (place/move) rejecting that off-cap cell — pinned as a coupling regression in
        // HomeLayoutTransitionMoveTest.add_to_home_onto_a_full_home_is_rejected. This
        // fixes the previous ceiling at pages == 2 (all_pages_full_returns_a_fresh_trailing_page).
        val items = (0 until HomeLayout.MAX_PAGES).flatMap { p -> (0 until 24).map { at(it, page = p) } }
        val full = layout(items = items, pages = HomeLayout.MAX_PAGES)
        assertThat(full.firstFreeCell()).isEqualTo(CellPos(HomeLayout.MAX_PAGES, 0, 0))
    }
}
