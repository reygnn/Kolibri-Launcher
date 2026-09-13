package com.github.reygnn.nyx_launcher.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for [homeCellHeightPx] — the fixed cell height shared by the grid
 * renderer and the drop-mapping geometry. The logic is a `min(target, equalSplit)`
 * with two zero-guards; both branches and both guards are pinned here so a change to
 * the [HOME_CELL_TARGET_DP] target or the split can't silently drift the geometry.
 */
class HomeCellHeightPxTest {

    @Test fun uses_the_target_height_when_it_fits_within_an_equal_split() {
        // Tall page: equal split (2000/5 = 400) exceeds the 110dp target → target wins,
        // leaving the rest as top padding (bottom-anchored grid).
        assertThat(homeCellHeightPx(pageHeightPx = 2000, rows = 5, density = 1f))
            .isEqualTo(HOME_CELL_TARGET_DP.toInt()) // 110
    }

    @Test fun falls_back_to_the_equal_split_on_a_short_screen() {
        // Short page: equal split (400/5 = 80) is below the 110dp target → split wins so
        // the rows still fit, and there is no top padding.
        assertThat(homeCellHeightPx(pageHeightPx = 400, rows = 5, density = 1f)).isEqualTo(80)
    }

    @Test fun target_equals_split_is_the_boundary() {
        // 110 rows-worth of page height: split == target == 110, min() is indifferent.
        assertThat(homeCellHeightPx(pageHeightPx = 110 * 5, rows = 5, density = 1f)).isEqualTo(110)
    }

    @Test fun density_scales_the_target() {
        // The target is dp → px via density; a denser screen wants a taller cell, still
        // capped by the equal split. 110dp @2x = 220px, and 2000/5 = 400 leaves room.
        assertThat(homeCellHeightPx(pageHeightPx = 2000, rows = 5, density = 2f)).isEqualTo(220)
    }

    @Test fun non_positive_rows_returns_zero() {
        assertThat(homeCellHeightPx(pageHeightPx = 600, rows = 0, density = 1f)).isEqualTo(0)
        assertThat(homeCellHeightPx(pageHeightPx = 600, rows = -3, density = 1f)).isEqualTo(0)
    }

    @Test fun non_positive_page_height_returns_zero() {
        assertThat(homeCellHeightPx(pageHeightPx = 0, rows = 5, density = 1f)).isEqualTo(0)
        assertThat(homeCellHeightPx(pageHeightPx = -100, rows = 5, density = 1f)).isEqualTo(0)
    }

    @Test fun a_page_shorter_than_its_row_count_collapses_the_cell_to_zero() {
        // pageHeight (3) < rows (5) → equal split truncates to 0 → cell height 0. This is
        // the value gridCellAt treats as "not yet laid out" and turns into null.
        assertThat(homeCellHeightPx(pageHeightPx = 3, rows = 5, density = 1f)).isEqualTo(0)
    }
}
