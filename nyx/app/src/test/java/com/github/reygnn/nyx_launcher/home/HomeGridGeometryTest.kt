package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure JVM tests for the grid-drop geometry (HOME_DRAG_ENGINE_SPEC §9). */
class HomeGridGeometryTest {

    // 400×600 page, 4×5 grid, density 1 → cell 110 high (min(110, 600/5=120)),
    // top dead space 600−5·110 = 50, column width 400/4 = 100.
    private val grid = GridSpec(columns = 4, rows = 5)
    private fun cell(x: Float, y: Float, page: Int = 0) =
        gridCellAt(page, x, y, pageWidth = 400, pageHeight = 600, grid = grid, density = 1f)

    @Test fun top_left_of_grid_content() {
        // localY = topPad (50) is the first row's top edge.
        assertThat(cell(0f, 50f)).isEqualTo(CellPos(0, 0, 0))
    }

    @Test fun mid_cell() {
        assertThat(cell(250f, 160f)).isEqualTo(CellPos(0, 2, 1))
    }

    @Test fun point_in_top_dead_space_clamps_to_row_0() {
        assertThat(cell(150f, 0f)).isEqualTo(CellPos(0, 1, 0))
    }

    @Test fun out_of_bounds_clamps_to_edge_cell() {
        assertThat(cell(999f, 999f)).isEqualTo(CellPos(0, 3, 4))
    }

    @Test fun negative_x_clamps_to_col_0() {
        assertThat(cell(-20f, 300f)).isEqualTo(CellPos(0, 0, (300 - 50) / 110))
    }

    @Test fun page_is_passed_through() {
        assertThat(cell(50f, 100f, page = 2)?.page).isEqualTo(2)
    }

    @Test fun short_page_shrinks_cells_no_dead_space() {
        // 400 high / 5 rows = 80 < target 110 → cell 80, topPad 0.
        val out = gridCellAt(0, 10f, 399f, pageWidth = 400, pageHeight = 400, grid = grid, density = 1f)
        assertThat(out).isEqualTo(CellPos(0, 0, 4)) // 399/80 = 4.98 → 4
    }

    @Test fun not_laid_out_returns_null() {
        assertThat(gridCellAt(0, 10f, 10f, 0, 600, grid, 1f)).isNull()
        assertThat(gridCellAt(0, 10f, 10f, 400, 0, grid, 1f)).isNull()
    }

    @Test fun degenerate_grid_returns_null_instead_of_crashing() {
        // A non-positive axis (a persisted/seeded GridSpec that never came from a real
        // device measurement) must return null — the function is documented as total.
        // columns == 0 used to divide by zero → +Inf → coerceIn(0, -1) → crash.
        assertThat(gridCellAt(0, 50f, 50f, 400, 600, GridSpec(columns = 0, rows = 5), 1f)).isNull()
        assertThat(gridCellAt(0, 50f, 50f, 400, 600, GridSpec(columns = 4, rows = 0), 1f)).isNull()
        // And the classifier that delegates to it stays null-safe too.
        assertThat(gridDropAt(0, 50f, 50f, 400, 600, GridSpec(columns = 0, rows = 5), 1f)).isNull()
    }

    // ---- gridDropAt: centre = Cell (place/folder), edges = GridInsert (reorder) ----

    private fun drop(x: Float, y: Float) =
        gridDropAt(0, x, y, pageWidth = 400, pageHeight = 600, grid = grid, density = 1f)

    @Test fun cell_centre_is_a_cell_target() {
        // col 0 (0..100), fx=0.5 → centre → land ON the cell.
        assertThat(drop(50f, 60f)).isEqualTo(DropTarget.Cell(CellPos(0, 0, 0)))
    }

    @Test fun left_edge_inserts_before_the_cell() {
        // col 0, fx=0.1 (<0.2) → insert at this cell's linear index (li = 0).
        assertThat(drop(10f, 60f)).isEqualTo(DropTarget.GridInsert(0, 0))
    }

    @Test fun right_edge_inserts_after_the_cell() {
        // col 0, fx=0.95 (>0.8) → insert after (li + 1 = 1).
        assertThat(drop(95f, 60f)).isEqualTo(DropTarget.GridInsert(0, 1))
    }

    @Test fun between_two_icons_resolves_to_the_same_index_from_either_side() {
        // Right edge of col 0 and left edge of col 1 both insert at index 1.
        assertThat(drop(98f, 60f)).isEqualTo(DropTarget.GridInsert(0, 1))
        assertThat(drop(102f, 60f)).isEqualTo(DropTarget.GridInsert(0, 1))
    }

    @Test fun edge_on_second_row_uses_linear_index() {
        // Row 1, col 2 → li = 1*4 + 2 = 6; left edge inserts before it.
        assertThat(drop(210f, 160f)).isEqualTo(DropTarget.GridInsert(0, 6))
    }

    @Test fun right_edge_of_the_last_column_inserts_at_the_next_row_start() {
        // Last column (col 3, 300..400), fx=0.95 → li+1. Row 0: li=3 → li+1=4 (row 1, col 0).
        assertThat(drop(395f, 60f)).isEqualTo(DropTarget.GridInsert(0, 4))
    }

    @Test fun far_right_out_of_bounds_x_clamps_the_cell_but_still_reads_as_after() {
        // x way past the page: cell.x clamps to col 3, but fx is computed from the raw x
        // (huge) → > 0.8 → insert after → same li+1 = 4 as the right-edge case.
        assertThat(drop(999f, 60f)).isEqualTo(DropTarget.GridInsert(0, 4))
    }

    @Test fun right_edge_of_the_bottom_right_cell_produces_the_past_last_cell_index() {
        // The very last cell (col 3, row 4 → li = 4*4+3 = 19) on its right edge inserts
        // AFTER it: li+1 = 20 == columns*rows. DropTarget.GridInsert documents this
        // "past the last cell" value, and insertOnGrid/gridInsertCell handle it — this
        // pins that the geometry actually emits it. (400×600, 4×5: col 3 = 300..400,
        // row 4 = 490..600; fx 0.95 > 0.8.)
        assertThat(drop(395f, 550f)).isEqualTo(DropTarget.GridInsert(0, grid.columns * grid.rows))
    }

    @Test fun negative_x_reads_as_before_the_first_cell() {
        // x < 0: cell.x clamps to col 0, fx < 0 (< 0.2) → insert before → li = 0.
        assertThat(drop(-50f, 60f)).isEqualTo(DropTarget.GridInsert(0, 0))
    }

    // ---- gridCellAt: further boundaries ----

    @Test fun exact_column_boundary_falls_into_the_next_column() {
        // localX == cellWidth (100) → (100/100).toInt() == 1 → col 1, not col 0.
        assertThat(cell(100f, 60f)).isEqualTo(CellPos(0, 1, 0))
    }

    @Test fun exact_row_boundary_falls_into_the_next_row() {
        // localY == topPad + cellHeight (50 + 110 = 160) → (110/110).toInt() == 1 → row 1.
        assertThat(cell(50f, 160f)).isEqualTo(CellPos(0, 0, 1))
    }

    @Test fun a_page_shorter_than_its_row_count_is_treated_as_not_laid_out() {
        // pageHeight (3) < rows (5) → cell height collapses to 0 → null (cf. homeCellHeightPx).
        assertThat(gridCellAt(0, 10f, 1f, pageWidth = 400, pageHeight = 3, grid = grid, density = 1f)).isNull()
    }

    @Test fun density_shifts_the_top_padding_but_keeps_the_mapping_consistent() {
        // @2x on a tall page: cell 220 high (110dp·2, < split 1200/5=240), topPad = 1200−5·220 = 100.
        // A point at localY == topPad is the first row's top edge; one cell down is row 1.
        val g = GridSpec(columns = 4, rows = 5)
        assertThat(gridCellAt(0, 0f, 100f, pageWidth = 400, pageHeight = 1200, grid = g, density = 2f))
            .isEqualTo(CellPos(0, 0, 0))
        assertThat(gridCellAt(0, 0f, 100f + 220f, pageWidth = 400, pageHeight = 1200, grid = g, density = 2f))
            .isEqualTo(CellPos(0, 0, 1))
    }

    // ---- gridDropAt: further edges ----

    @Test fun single_column_grid_classifies_left_centre_and_right() {
        val g = GridSpec(columns = 1, rows = 5) // colWidth == pageWidth (100)
        fun d(x: Float) = gridDropAt(0, x, 60f, pageWidth = 100, pageHeight = 600, grid = g, density = 1f)
        assertThat(d(10f)).isEqualTo(DropTarget.GridInsert(0, 0)) // fx 0.1 → before
        assertThat(d(50f)).isEqualTo(DropTarget.Cell(CellPos(0, 0, 0))) // fx 0.5 → on cell
        assertThat(d(90f)).isEqualTo(DropTarget.GridInsert(0, 1)) // fx 0.9 → after
    }

    @Test fun fraction_boundaries_are_inclusive_of_the_centre_band() {
        // fx == 0.2 and fx == 0.8 are NOT strictly beyond the edge fractions, so both
        // land ON the cell (the < / > comparisons are exclusive). col 0 = 0..100.
        assertThat(drop(20f, 60f)).isEqualTo(DropTarget.Cell(CellPos(0, 0, 0))) // fx == 0.2
        assertThat(drop(80f, 60f)).isEqualTo(DropTarget.Cell(CellPos(0, 0, 0))) // fx == 0.8
    }

    @Test fun a_drop_in_the_top_dead_space_still_classifies_against_row_0() {
        // gridCellAt clamps a point above the grid content (localY < topPad = 50) to row 0
        // (point_in_top_dead_space_clamps_to_row_0). This pins that the CLASSIFIER agrees:
        // a centre-x drop up there lands ON row-0's cell, an edge-x drop reorders at its
        // index — the dead space never yields null or a negative row.
        assertThat(drop(50f, 0f)).isEqualTo(DropTarget.Cell(CellPos(0, 0, 0))) // col 0 centre
        assertThat(drop(95f, 0f)).isEqualTo(DropTarget.GridInsert(0, 1)) // col 0 right edge → after
    }

    @Test fun degenerate_rows_make_the_classifier_null_too() {
        // Symmetry with the columns==0 guard: a non-positive row count also yields null.
        assertThat(gridDropAt(0, 50f, 50f, 400, 600, GridSpec(columns = 4, rows = 0), 1f)).isNull()
    }
}
