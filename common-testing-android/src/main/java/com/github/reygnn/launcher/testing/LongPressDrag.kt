package com.github.reygnn.launcher.testing

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.espresso.UiController

/**
 * Injects a Launcher3-style long-press-drag as a raw MotionEvent stream through
 * [uiController]: press and hold past the long-press timeout (so a
 * long-press-armed drag engine promotes the touch to a drag), then move through
 * [waypoints] in screen coordinates, then lift at the last waypoint.
 *
 * All coordinates are absolute screen coordinates (as from
 * `View.getLocationOnScreen`); the touch-owning root offsets them to its own
 * space during dispatch. [dwellMsPerWaypoint] holds the finger still at each
 * waypoint before moving on — needed for edge-triggered behaviour like a pager
 * advancing while a drag hovers at its edge.
 *
 * Product-neutral: it only injects touches, so it drives any long-press drag
 * engine (nyx's DragLayer/DragController, a future kolibri one, …). For the
 * ItemTouchHelper case use [dragRecyclerItem] instead.
 */
public fun longPressDrag(
    uiController: UiController,
    startX: Float,
    startY: Float,
    waypoints: List<Pair<Float, Float>>,
    dwellMsPerWaypoint: Long = 0,
    stepsPerLeg: Int = 12,
) {
    require(waypoints.isNotEmpty()) { "longPressDrag needs at least one waypoint" }

    val downTime = SystemClock.uptimeMillis()
    fun send(action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        uiController.injectMotionEvent(event)
        event.recycle()
    }

    send(MotionEvent.ACTION_DOWN, startX, startY)
    // Hold in place past the long-press timeout so the engine arms the drag
    // before any movement is read as a scroll/swipe.
    uiController.loopMainThreadForAtLeast((ViewConfiguration.getLongPressTimeout() + 300).toLong())

    var curX = startX
    var curY = startY
    waypoints.forEachIndexed { index, (wx, wy) ->
        for (i in 1..stepsPerLeg) {
            val x = curX + (wx - curX) * i / stepsPerLeg
            val y = curY + (wy - curY) * i / stepsPerLeg
            send(MotionEvent.ACTION_MOVE, x, y)
            uiController.loopMainThreadForAtLeast(20)
        }
        curX = wx
        curY = wy
        // Dwell only at INTERMEDIATE waypoints (e.g. a pager edge), never at the
        // final drop point: holding still at the destination can re-trigger an
        // edge-advance (if the drop cell sits inside an edge zone) and carry the
        // item off the intended page before ACTION_UP. The drop lifts immediately.
        val isLast = index == waypoints.lastIndex
        if (dwellMsPerWaypoint > 0 && !isLast) {
            // Keep the finger down and still, re-emitting so edge timers keep firing.
            val until = SystemClock.uptimeMillis() + dwellMsPerWaypoint
            while (SystemClock.uptimeMillis() < until) {
                send(MotionEvent.ACTION_MOVE, curX, curY)
                uiController.loopMainThreadForAtLeast(30)
            }
        }
    }

    send(MotionEvent.ACTION_UP, curX, curY)
    uiController.loopMainThreadUntilIdle()
}
