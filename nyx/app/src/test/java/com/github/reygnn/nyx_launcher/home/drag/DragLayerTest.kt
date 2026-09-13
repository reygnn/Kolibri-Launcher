package com.github.reygnn.nyx_launcher.home.drag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.DragPayload
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric test for the [DragLayer.dispatchTouchEvent] regime switch
 * (DRG-INV-1, A1-10): while dragging it captures the whole stream and routes it
 * to the [DragController]; while idle it delegates to the shared gesture core.
 * The controller's own state machine is unit-tested behind the [DragViewHost]
 * seam; here we pin the DragLayer switch that a refactor could silently break.
 */
@RunWith(RobolectricTestRunner::class)
class DragLayerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val payload: DragPayload = DragPayload.Existing(ItemId("a"))

    /** A drag can only start from a laid-out source (drawToBitmap needs a size). */
    private fun laidOutSource(): View = ImageView(context).apply {
        setImageBitmap(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888))
        measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, 100, 100)
    }

    private class FakeZone(private val rect: Rect) : DropZone {
        var enter = 0
        var exit = 0
        var drop = 0
        var lastDropX = -1
        override fun hitRect(out: Rect) = out.set(rect)
        override fun accepts(payload: DragPayload) = true
        override fun onDragEnter() { enter++ }
        override fun onDragExit() { exit++ }
        override fun onDrop(payload: DragPayload, x: Int, y: Int) { drop++; lastDropX = x }
    }

    private fun event(action: Int, x: Float, y: Float, eventTime: Long = 0L): MotionEvent =
        MotionEvent.obtain(0L, eventTime, action, x, y, 0)

    @Test
    fun move_while_dragging_is_captured_and_routed_to_the_controller() {
        val dragLayer = DragLayer(context)
        val zone = FakeZone(Rect(0, 0, 500, 500))
        dragLayer.dragController.addDropZone(zone)

        // A real drag is always preceded by the ACTION_DOWN that latches the active
        // pointer id (DragLayer tracks the dragging finger by id, not index). Without
        // it the MOVE handler's findPointerIndex(INVALID) would drop the event.
        dragLayer.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 0f, 0f))
        dragLayer.startDrag(payload, laidOutSource()) // starts at (0,0), inside zone
        assertThat(dragLayer.dragController.isDragging).isTrue()
        assertThat(zone.enter).isEqualTo(1)

        // Moving outside the zone must reach the controller (updateZone -> exit).
        val handled = dragLayer.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 900f, 900f))

        assertThat(handled).isTrue()
        assertThat(zone.exit).isEqualTo(1)
    }

    @Test
    fun up_while_dragging_commits_the_drop_and_ends_the_drag() {
        val dragLayer = DragLayer(context)
        val zone = FakeZone(Rect(0, 0, 500, 500))
        dragLayer.dragController.addDropZone(zone)
        dragLayer.startDrag(payload, laidOutSource())

        val handled = dragLayer.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 100f, 100f))

        assertThat(handled).isTrue()
        assertThat(zone.drop).isEqualTo(1)
        assertThat(zone.lastDropX).isEqualTo(100)
        assertThat(dragLayer.dragController.isDragging).isFalse()
    }

    @Test
    fun cancel_while_dragging_ends_the_drag_without_a_drop() {
        val dragLayer = DragLayer(context)
        val zone = FakeZone(Rect(0, 0, 500, 500))
        dragLayer.dragController.addDropZone(zone)
        dragLayer.startDrag(payload, laidOutSource())

        val handled = dragLayer.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, 0f, 0f))

        assertThat(handled).isTrue()
        assertThat(dragLayer.dragController.isDragging).isFalse()
        assertThat(zone.drop).isEqualTo(0)
    }

    @Test
    fun idle_delegates_to_the_gesture_core_and_never_engages_the_controller() {
        val dragLayer = DragLayer(context)
        var swipedUp = false
        dragLayer.onSwipeUp = { swipedUp = true }

        // A fast upward swipe on empty space must reach the gesture core, not the
        // (idle) drag controller.
        dragLayer.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 1500f))
        dragLayer.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 100f, 100f, eventTime = 10L))

        assertThat(swipedUp).isTrue()
        assertThat(dragLayer.dragController.isDragging).isFalse()
    }
}
