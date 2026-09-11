package com.github.reygnn.nyx_launcher.home.transition

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.model.RegridOutcome
import com.github.reygnn.nyx_launcher.home.model.Span

/**
 * Pure transition that re-fits a [HomeLayout] onto a device-derived [GridSpec]
 * (ICON_HOME_MODEL_SPEC §10). Android-free, total, deterministic, idempotent.
 *
 * Policy — lossless and minimally disruptive:
 *  - The grid dimensions become [target].
 *  - Grid items already inside the new bounds keep their exact [CellPos]; only
 *    items that fall off-grid (x ≥ columns or y ≥ rows) are relocated.
 *  - Dock capacity is `columns`; overflow dock items are re-homed onto the grid
 *    rather than dropped (never lose an app — cf. HEU-INV-2).
 *  - Relocated items (off-grid grid items in page/y/x order, then dock overflow)
 *    fill the first free cells in row-major order across pages, adding pages as
 *    needed. Their [Span] is preserved.
 *
 * Growing the grid moves nothing (everything stays in bounds, only the grid
 * dimensions update). Idempotent: re-fitting a layout to its own grid is a
 * no-op ([RegridOutcome.Unchanged]).
 */
object HomeLayoutRegridder {

    fun fit(layout: HomeLayout, target: GridSpec): RegridOutcome {
        if (layout.grid == target) return RegridOutcome.Unchanged

        val cols = target.columns
        val rows = target.rows

        // Dock capacity is `columns`; the rest is re-homed onto the grid.
        val dockKept = layout.dock.take(cols)
        val dockOverflow = layout.dock.drop(cols)

        // In-bounds grid items keep their position; the rest join the queue.
        val inBounds = ArrayList<PlacedItem>()
        val offGrid = ArrayList<PlacedItem>()
        for (placed in layout.items) {
            if (placed.pos.x in 0 until cols && placed.pos.y in 0 until rows) {
                inBounds.add(placed)
            } else {
                offGrid.add(placed)
            }
        }
        offGrid.sortWith(compareBy({ it.pos.page }, { it.pos.y }, { it.pos.x }))

        // Relocation queue: off-grid grid items first (span preserved), then
        // dock overflow (default span). Neither is ever dropped.
        val queue = ArrayList<Pair<HomeItem, Span>>(offGrid.size + dockOverflow.size)
        offGrid.forEach { queue.add(it.item to it.span) }
        dockOverflow.forEach { queue.add(it to Span()) }

        val occupied = HashSet<CellPos>().apply { inBounds.forEach { add(it.pos) } }
        val relocated = ArrayList<PlacedItem>(queue.size)
        var page = 0
        var x = 0
        var y = 0
        fun advance() {
            x++
            if (x >= cols) { x = 0; y++ }
            if (y >= rows) { y = 0; page++ }
        }
        for ((item, span) in queue) {
            var cell = CellPos(page, x, y)
            while (cell in occupied) {
                advance()
                cell = CellPos(page, x, y)
            }
            relocated.add(PlacedItem(item, cell, span))
            occupied.add(cell)
            advance()
        }

        val newItems = inBounds + relocated
        val newPages = maxOf(1, newItems.maxOfOrNull { it.pos.page + 1 } ?: 0)
        val newLayout = layout.copy(grid = target, pages = newPages, items = newItems, dock = dockKept)
        return if (newLayout == layout) RegridOutcome.Unchanged else RegridOutcome.Changed(newLayout)
    }
}
