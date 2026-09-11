package com.github.reygnn.nyx_launcher.data.home

import android.content.Context
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.repository.GridSpecProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Device-derived [GridSpec]: columns/rows from the screen size divided by a
 * target cell edge (~[TARGET_DP]), each clamped to a sane range so phones and
 * small tablets get a proportional grid instead of a fixed 4×6
 * (ICON_HOME_MODEL_SPEC §10).
 *
 * The row estimate subtracts the dock and a system-bar allowance from the screen
 * height; it only needs to be approximate, because the cells then stretch to fill
 * the real page height at render time (HomeGridAdapter) and the drop math derives
 * its cell size from the same page geometry (MainActivity.resolveGridCell).
 */
class GridSpecProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : GridSpecProvider {

    override fun deviceGrid(): GridSpec {
        val dm = context.resources.displayMetrics
        val density = dm.density.takeIf { it > 0f } ?: 1f
        val widthDp = dm.widthPixels / density
        val heightDp = dm.heightPixels / density
        val availHeightDp = heightDp - DOCK_DP - CHROME_DP

        val columns = (widthDp / TARGET_DP).toInt().coerceIn(MIN_COLS, MAX_COLS)
        val rows = (availHeightDp / TARGET_DP).toInt().coerceIn(MIN_ROWS, MAX_ROWS)
        return GridSpec(columns = columns, rows = rows)
    }

    private companion object {
        const val TARGET_DP = 110f
        const val DOCK_DP = 88f     // dock height (activity_main.xml)
        const val CHROME_DP = 96f   // status + navigation bar allowance (heuristic)
        const val MIN_COLS = 3
        const val MAX_COLS = 6
        const val MIN_ROWS = 4
        const val MAX_ROWS = 8
    }
}
