package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.nyx_launcher.home.model.CellPos
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
}
