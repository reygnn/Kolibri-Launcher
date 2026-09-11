package com.github.reygnn.nyx_launcher.home.drag

import android.view.View

/**
 * The drag view surface the [DragController] drives: add a floating copy of the
 * dragged view, move it, remove it. Implemented by [DragLayer]; abstracted so the
 * controller's state machine is testable without a real view (HOME_DRAG_ENGINE_SPEC §9).
 */
interface DragViewHost {
    fun addDragView(source: View, x: Int, y: Int)
    fun moveDragView(x: Int, y: Int)
    fun removeDragView()
}
