package com.github.reygnn.launcher.testing

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import org.hamcrest.Matcher

/**
 * Espresso [ViewAction] that drags the item at [fromPosition] onto [toPosition]
 * inside a RecyclerView, via a genuine long-press-then-move touch stream — the
 * gesture an `ItemTouchHelper` with long-press drag enabled reacts to.
 *
 * Injected as raw [MotionEvent]s (DOWN, a hold past the long-press timeout so the
 * helper enters drag mode, stepped MOVEs across the neighbours, UP) because
 * neither Espresso core nor RecyclerViewActions ships a drag, and only a real
 * touch stream exercises the nested `onMove` chain. Reproducing that stream is
 * exactly what a device test buys over Robolectric (which never dispatches it).
 *
 * Coordinates are read from the live view holders at perform time, so the caller
 * only supplies adapter positions. Both positions must currently be laid out
 * (small, fully-visible lists — the favorites-reorder case).
 */
public fun dragRecyclerItem(fromPosition: Int, toPosition: Int): ViewAction = object : ViewAction {

    override fun getConstraints(): Matcher<View> = isAssignableFrom(RecyclerView::class.java)

    override fun getDescription(): String = "drag RecyclerView item $fromPosition -> $toPosition"

    override fun perform(uiController: UiController, view: View) {
        val recycler = view as RecyclerView
        val fromView = recycler.findViewHolderForAdapterPosition(fromPosition)?.itemView
            ?: error("No laid-out view holder at position $fromPosition")
        val toView = recycler.findViewHolderForAdapterPosition(toPosition)?.itemView
            ?: error("No laid-out view holder at position $toPosition")

        val startX = centerX(fromView)
        val startY = centerY(fromView)
        // Overshoot past the target row's midpoint (in the drag direction) so the
        // dragged view clears every intervening neighbour's swap threshold, not
        // just the first — ItemTouchHelper swaps once per midpoint crossed.
        val direction = if (toPosition >= fromPosition) 1f else -1f
        val endY = centerY(toView) + direction * (toView.height * 0.6f)

        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            uiController.injectMotionEvent(event)
            event.recycle()
        }

        // Press and hold past the long-press timeout so ItemTouchHelper promotes
        // the touch to a drag before any movement is interpreted as a scroll.
        send(MotionEvent.ACTION_DOWN, startX, startY)
        uiController.loopMainThreadForAtLeast((ViewConfiguration.getLongPressTimeout() + 300).toLong())

        // Step to the target row so onMove fires for each neighbour crossed.
        val steps = 12
        for (i in 1..steps) {
            val y = startY + (endY - startY) * i / steps
            send(MotionEvent.ACTION_MOVE, startX, y)
            uiController.loopMainThreadForAtLeast(20)
        }

        send(MotionEvent.ACTION_UP, startX, endY)
        uiController.loopMainThreadUntilIdle()
    }

    private fun centerX(v: View): Float {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return loc[0] + v.width / 2f
    }

    private fun centerY(v: View): Float {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return loc[1] + v.height / 2f
    }
}
