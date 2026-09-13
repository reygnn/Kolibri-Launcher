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
 *  - Placement is hard-capped at [HomeLayout.MAX_PAGES] (page indices
 *    `0 until MAX_PAGES`), mirroring the move transition's page cap. An item that
 *    would only fit past the cap is dropped from the home layout rather than
 *    placed on a page the pager never renders (`renderedPageCount` caps too) — a
 *    dropped app is NOT lost: the drawer lists every installed app regardless of
 *    home placement, so it stays reachable. This only bites a pathological refit
 *    (well over `MAX_PAGES × columns × rows` grid items shrunk onto a tiny grid);
 *    a folder dropped this way loses only its grouping, never its member apps.
 *
 * Growing the grid moves nothing (everything stays in bounds, only the grid
 * dimensions update). Idempotent: re-fitting a layout to its own grid is a
 * no-op ([RegridOutcome.Unchanged]).
 */
object HomeLayoutRegridder {

    fun fit(layout: HomeLayout, target: GridSpec): RegridOutcome {
        // Nothing to do only when the grid already matches AND the dock is within
        // capacity AND no item sits past the page cap — an over-capacity dock (e.g. a
        // first-run seed of more apps than the measured grid is wide) still needs its
        // overflow re-homed, and a stale item on a page the pager never renders
        // (pre-cap build) must be pulled back even when the grid is unchanged, or it
        // would stay unreachable. Normal layouts satisfy all three, so this stays a
        // no-op on every routine layout pass (no persist storm).
        if (layout.grid == target &&
            layout.dock.size <= target.columns &&
            layout.items.none { it.pos.page >= HomeLayout.MAX_PAGES }
        ) {
            return RegridOutcome.Unchanged
        }

        val cols = target.columns
        val rows = target.rows

        // Dock capacity is `columns`; the rest is re-homed onto the grid.
        val dockKept = layout.dock.take(cols)
        val dockOverflow = layout.dock.drop(cols)

        // In-bounds grid items keep their position; the rest join the queue. A cell
        // beyond the page cap counts as off-grid too, so a layout that somehow carries
        // an item past MAX_PAGES (stale persist, pre-cap build) is pulled back onto a
        // reachable page rather than kept on one the pager never renders.
        val inBounds = ArrayList<PlacedItem>()
        val offGrid = ArrayList<PlacedItem>()
        for (placed in layout.items) {
            if (placed.pos.page in 0 until HomeLayout.MAX_PAGES &&
                placed.pos.x in 0 until cols && placed.pos.y in 0 until rows
            ) {
                inBounds.add(placed)
            } else {
                offGrid.add(placed)
            }
        }
        offGrid.sortWith(compareBy({ it.pos.page }, { it.pos.y }, { it.pos.x }))

        // Relocation queue: off-grid grid items first (span preserved), then dock
        // overflow (default span). Both are placed unless the page cap is hit below.
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
            while (page < HomeLayout.MAX_PAGES && cell in occupied) {
                advance()
                cell = CellPos(page, x, y)
            }
            // No free cell left within the page cap: every remaining queue item is
            // dropped from the home layout (still reachable via the drawer). Row-major
            // fill means once we spill past the cap, nothing after it can fit either.
            if (page >= HomeLayout.MAX_PAGES) break
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
