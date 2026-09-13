package com.github.reygnn.nyx_launcher.home.model

/**
 * Where a drag was dropped. See MOVE_ITEM_SPEC §2.
 *
 * [DockSlot] is seatless — it indexes into the flat [HomeLayout.dock] list and
 * carries no [CellPos].
 */
sealed interface DropTarget {
    /**
     * Land ON a single cell: an empty cell places there, an app makes a folder, a
     * folder adds a member (MOVE_ITEM_SPEC §3.1). Produced when the drop is over
     * the central ~60% of a cell.
     */
    data class Cell(val pos: CellPos) : DropTarget

    /**
     * INSERT at a reading-order position on [page], shifting the occupant there —
     * and the ones after it — one cell forward until a gap absorbs the shift (a
     * Launcher3-style reorder), overflowing to the next page only if the page is
     * dense. [index] is the linear cell index `y*columns + x`, in `0..columns*rows`
     * ( == columns*rows means "past the last cell"). Produced when the drop lands
     * near a cell edge / between two icons, so it reorders instead of foldering.
     */
    data class GridInsert(val page: Int, val index: Int) : DropTarget

    /** Seatless dock slot: inserts into the flat [HomeLayout.dock] list at [index]. */
    data class DockSlot(val index: Int) : DropTarget
}
