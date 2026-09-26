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
 *    items that fall off-grid (x ≥ columns or y ≥ rows) are relocated. An in-bounds
 *    item whose cell is already taken by an earlier item is a same-cell collision
 *    (only an imported/hand-edited blob can carry one — transitions keep one item per
 *    cell); the first claimant keeps the cell, the later one is relocated so it can't
 *    stay hidden under the winner (pageCells renders last-wins).
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
        // capacity AND every item is fully on-grid — an over-capacity dock (e.g. a
        // first-run seed of more apps than the measured grid is wide) still needs its
        // overflow re-homed, and any item the loop below would classify as off-grid
        // must be pulled back even when the grid is unchanged, or it stays unreachable.
        // "Off-grid" covers a stale page past the cap (a pre-cap build) AND a stale
        // x/y outside the grid bounds: the regridder itself never emits the latter on a
        // matching grid, but an imported/restored/hand-edited blob (NyxBackupManager
        // restore) is saved verbatim with no coordinate clamp, so a blob whose
        // stored grid equals the measured device grid can carry a spatially off-grid
        // item — which pageCells would then silently alias onto another cell or drop
        // from view. This predicate mirrors the off-grid partition below exactly, so the
        // guard trusts a layout as a no-op only when the relocation loop would have moved
        // nothing. Normal layouts satisfy all three terms, so this stays a no-op on every
        // routine layout pass (no persist storm).
        if (layout.grid == target &&
            layout.dock.size <= target.columns &&
            layout.items.none {
                it.pos.page !in 0 until HomeLayout.MAX_PAGES ||
                    it.pos.x !in 0 until target.columns ||
                    it.pos.y !in 0 until target.rows
            } &&
            // …and no two items share a cell. A same-cell collision only arises from an
            // imported/hand-edited blob (transitions keep one item per cell); left in place
            // the loser stays hidden under the winner forever (pageCells renders last-wins,
            // reconcile dedups only by ComponentKey, so nothing else heals it). Mirror the
            // relocation partition below, which would move the collider, so the guard trusts a
            // matching grid as a no-op only when that loop truly moves nothing.
            layout.items.mapTo(HashSet<CellPos>()) { it.pos }.size == layout.items.size
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
        val claimed = HashSet<CellPos>()
        for (placed in layout.items) {
            val onGrid = placed.pos.page in 0 until HomeLayout.MAX_PAGES &&
                placed.pos.x in 0 until cols && placed.pos.y in 0 until rows
            // An in-bounds cell already claimed by an earlier item is a same-cell collision
            // (imported/hand-edited blob only). Keep the first claimant; the later one joins
            // the relocation queue so it lands on a free cell instead of staying hidden under
            // the winner — symmetric to how a spatially off-grid item is pulled back.
            if (onGrid && claimed.add(placed.pos)) {
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

        val occupied = claimed // already exactly the kept in-bounds cells
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
