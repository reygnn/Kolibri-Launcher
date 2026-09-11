package com.github.reygnn.nyx_launcher.home.drag

import android.graphics.Rect
import com.github.reygnn.nyx_launcher.home.DragPayload

/**
 * A place a drag can be dropped, hit-tested by the [DragController]
 * (HOME_DRAG_ENGINE_SPEC §4, "DropTarget"). Named `DropZone` to stay distinct
 * from the domain `DropTarget` (Cell/DockSlot), which is the *model* location a
 * drop maps to.
 *
 * The controller picks the first registered zone whose [hitRect] contains the
 * point and that [accepts] the payload — registration order is priority.
 */
interface DropZone {
    /** Fills [out] with this zone's hit rectangle, in [DragLayer] coordinates. */
    fun hitRect(out: Rect)

    fun accepts(payload: DragPayload): Boolean

    /** The drag entered this zone (single active zone at a time). */
    fun onDragEnter() {}

    /** The drag left this zone (or the drag ended). */
    fun onDragExit() {}

    /** Commit the drop at ([x], [y]) (DragLayer coords). Called once. */
    fun onDrop(payload: DragPayload, x: Int, y: Int)
}
