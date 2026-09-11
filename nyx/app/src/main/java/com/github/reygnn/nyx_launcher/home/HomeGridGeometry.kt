package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec

/**
 * Pure grid-drop geometry (HOME_DRAG_ENGINE_SPEC §9): maps a point on a page —
 * [localX]/[localY] relative to that page view's top-left — to the [CellPos] it
 * falls in. Android-free and total, so it is JVM-testable; the view glue
 * (finding the page view and its offset) stays in MainActivity.resolveGridCell.
 *
 * Mirrors how the grid renders (HomeGridAdapter): a fixed cell height
 * ([homeCellHeightPx]) with the grid bottom-anchored, so the leftover top
 * padding is subtracted before the row is computed. Column width is the page
 * width split evenly. Out-of-range points clamp to the nearest edge cell.
 *
 * Returns null only for a not-yet-laid-out page (non-positive size / cell).
 */
internal fun gridCellAt(
    page: Int,
    localX: Float,
    localY: Float,
    pageWidth: Int,
    pageHeight: Int,
    grid: GridSpec,
    density: Float,
): CellPos? {
    if (pageWidth <= 0 || pageHeight <= 0) return null
    val cellHeight = homeCellHeightPx(pageHeight, grid.rows, density)
    if (cellHeight <= 0) return null
    val cellWidth = pageWidth.toFloat() / grid.columns
    if (cellWidth <= 0f) return null

    val topPad = pageHeight - grid.rows * cellHeight
    val col = (localX / cellWidth).toInt().coerceIn(0, grid.columns - 1)
    val row = ((localY - topPad) / cellHeight).toInt().coerceIn(0, grid.rows - 1)
    return CellPos(page, col, row)
}
