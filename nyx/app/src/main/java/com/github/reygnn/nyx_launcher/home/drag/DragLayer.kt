package com.github.reygnn.nyx_launcher.home.drag

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.drawToBitmap
import com.github.reygnn.launcher.common.ui.gesture.GestureDispatchCore
import com.github.reygnn.nyx_launcher.home.DragPayload

/**
 * Touch-owning home root (HOME_DRAG_ENGINE_SPEC §2/§3). One `dispatchTouchEvent`
 * carries both regimes:
 *  - **idle:** it runs the shared [GestureDispatchCore] (swipe-up / long-press on
 *    empty space) exactly like the common `GestureFrameLayout` would;
 *  - **dragging:** once [startDrag] has been called (from an icon long-press) it
 *    captures the whole stream itself and forwards it to the [DragController],
 *    never handing the gesture to a system window (DRG-INV-1). Because the touch
 *    was captured on ACTION_DOWN in this window, MOVE/UP keep coming even over the
 *    status bar, so drops work up to the display's top edge.
 *
 * Gestures are implicitly gated during a drag: the core is not consulted while
 * dragging, so no swipe can fire mid-drag.
 */
class DragLayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val gestureCore = GestureDispatchCore(this)
    val dragController = DragController(this)

    private var lastX = 0f
    private var lastY = 0f
    private var dragView: ImageView? = null
    private var dragSource: View? = null
    private var dragWidth = 0
    private var dragHeight = 0

    var onSwipeUp: (() -> Unit)?
        get() = gestureCore.onSwipeUp
        set(value) { gestureCore.onSwipeUp = value }
    var onLongPress: (() -> Unit)?
        get() = gestureCore.onLongPress
        set(value) { gestureCore.onLongPress = value }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastX = ev.x
        lastY = ev.y
        if (dragController.isDragging) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> dragController.onMove(ev.x.toInt(), ev.y.toInt())
                MotionEvent.ACTION_UP -> dragController.onDrop(ev.x.toInt(), ev.y.toInt())
                MotionEvent.ACTION_CANCEL -> dragController.onCancel()
            }
            return true
        }
        return gestureCore.dispatch(ev) { super.dispatchTouchEvent(it) }
    }

    /**
     * Begin a drag of [source] from the current touch position. Call from the
     * source's long-press. Sends the pressing child an ACTION_CANCEL first so it
     * releases the gesture cleanly, then hands off to the controller.
     */
    fun startDrag(payload: DragPayload, source: View) {
        val cancel = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, lastX, lastY, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
        dragController.startDrag(payload, source, lastX.toInt(), lastY.toInt())
    }

    // ---- drag view (called by DragController) ----

    internal fun addDragView(source: View, x: Int, y: Int) {
        removeDragView() // clear a leftover from a not-yet-settled previous drop
        dragSource = source
        dragWidth = source.width
        dragHeight = source.height
        val bitmap = source.drawToBitmap()
        val view = ImageView(context).apply {
            setImageBitmap(bitmap)
            layoutParams = LayoutParams(dragWidth, dragHeight)
            isClickable = false
        }
        addView(view)
        dragView = view
        source.visibility = INVISIBLE
        moveDragView(x, y)
    }

    internal fun moveDragView(x: Int, y: Int) {
        val view = dragView ?: return
        // Centre on the finger using the known source size — the ImageView isn't
        // laid out yet on the first call, so its own width/height would be 0.
        view.translationX = (x - dragWidth / 2).toFloat()
        view.translationY = (y - dragHeight / 2).toFloat()
    }

    internal fun removeDragView() {
        dragView?.let(::removeView)
        dragView = null
        dragSource?.visibility = VISIBLE
        dragSource = null
    }
}
