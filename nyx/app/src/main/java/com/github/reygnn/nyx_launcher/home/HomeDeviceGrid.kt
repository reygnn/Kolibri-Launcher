package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.nyx_launcher.home.model.GridSpec

/**
 * Pure device-grid computation: maps the laid-out pager size to the home grid
 * ([GridSpec]) it should use — columns from a [HOME_COL_TARGET_DP] target width,
 * rows from a [HOME_CELL_TARGET_DP] target height, each clamped to the supported
 * range. Android-free and total, so it is JVM-testable; the view glue (reading
 * `pager.width`/`.height` and pushing the result into the ViewModel) stays in
 * MainActivity.applyDeviceGrid.
 *
 * Returns null for a not-yet-laid-out pager (non-positive size) or a degenerate
 * density, matching the old inline early-return.
 */
internal fun computeDeviceGrid(widthPx: Int, heightPx: Int, density: Float): GridSpec? {
    val colTargetPx = HOME_COL_TARGET_DP * density
    val rowTargetPx = HOME_CELL_TARGET_DP * density
    if (widthPx <= 0 || heightPx <= 0 || colTargetPx <= 0f || rowTargetPx <= 0f) return null
    val columns = (widthPx / colTargetPx).toInt().coerceIn(HOME_MIN_COLUMNS, HOME_MAX_COLUMNS)
    val rows = (heightPx / rowTargetPx).toInt().coerceIn(HOME_MIN_ROWS, HOME_MAX_ROWS)
    return GridSpec(columns = columns, rows = rows)
}
