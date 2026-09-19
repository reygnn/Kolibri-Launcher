package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure JVM tests for [computeDeviceGrid]. */
class HomeDeviceGridTest {

    // density 1 → col target 72px, row target 110px.
    @Test fun typical_phone_size_maps_to_a_clamped_grid() {
        // 1080/72 = 15 → clamped to HOME_MAX_COLUMNS (6); 2000/110 = 18 → clamped to HOME_MAX_ROWS (8).
        assertThat(computeDeviceGrid(1080, 2000, density = 1f)).isEqualTo(GridSpec(6, 8))
    }

    @Test fun mid_range_size_is_not_clamped() {
        // 300/72 = 4 columns (in 3..6), 550/110 = 5 rows (in 4..8).
        assertThat(computeDeviceGrid(300, 550, density = 1f)).isEqualTo(GridSpec(4, 5))
    }

    @Test fun tiny_size_clamps_up_to_the_minimum() {
        // 72/72 = 1 → clamped to HOME_MIN_COLUMNS (3); 110/110 = 1 → clamped to HOME_MIN_ROWS (4).
        assertThat(computeDeviceGrid(72, 110, density = 1f)).isEqualTo(GridSpec(3, 4))
    }

    @Test fun density_scales_the_targets() {
        // density 2 → col target 144px, row target 220px. 900/144 = 6 cols, 900/220 = 4 rows.
        assertThat(computeDeviceGrid(900, 900, density = 2f)).isEqualTo(GridSpec(6, 4))
    }

    @Test fun not_laid_out_pager_returns_null() {
        assertThat(computeDeviceGrid(0, 600, density = 1f)).isNull()
        assertThat(computeDeviceGrid(400, 0, density = 1f)).isNull()
    }

    @Test fun degenerate_density_returns_null() {
        assertThat(computeDeviceGrid(400, 600, density = 0f)).isNull()
    }
}
