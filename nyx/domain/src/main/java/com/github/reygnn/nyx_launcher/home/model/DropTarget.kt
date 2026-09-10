package com.github.reygnn.nyx_launcher.home.model

/**
 * Where a drag was dropped. See MOVE_ITEM_SPEC §2.
 *
 * [DockSlot] is seatless — it indexes into the flat [HomeLayout.dock] list and
 * carries no [CellPos].
 */
sealed interface DropTarget {
    data class Cell(val pos: CellPos) : DropTarget
    data class DockSlot(val index: Int) : DropTarget
}
