package com.github.reygnn.nyx_launcher.home.drag

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.VisibleForTesting
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
) : FrameLayout(context, attrs, defStyleAttr), DragViewHost {

    private val gestureCore = GestureDispatchCore(this)
    val dragController = DragController(this)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // Launcher3-style unified long-press: an icon long-press ARMS a drag and shows a
    // context menu. Moving past the slop promotes it to a real drag (menu dismissed);
    // lifting in place keeps the menu. Between arm and that decision, this layer owns
    // the stream (like a drag) so children don't scroll or re-trigger.
    private var armedPayload: DragPayload? = null
    private var armedSource: View? = null
    private var armX = 0f
    private var armY = 0f

    /**
     * Test-observation hook: true between a long-press arming a drag and its
     * promotion/disarm. Read-only reflection of [armedPayload]; production never
     * reads it. Lets androidTest await the arm→promote transition deterministically
     * (the arm/promote timing is the drawer-drag seam that plain gesture timing
     * couldn't hit reliably) instead of guessing a hold duration.
     */
    @get:VisibleForTesting
    internal val isDragArmed: Boolean get() = armedPayload != null

    /** Called when a long-press arms: show the context menu for [payload] at [source]. */
    var onArm: ((payload: DragPayload, source: View) -> Unit)? = null
    /**
     * Called when an armed press promotes to a drag (dismiss the menu; the host decides
     * whether to hide the drawer based on [payload] — a drawer-app fold keeps it open).
     */
    var onArmedPromote: ((payload: DragPayload) -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    // The pointer that started the current gesture (captured on ACTION_DOWN). Arm and
    // drag track THIS finger by id, not pointer index 0, so a second finger lifting the
    // first (ACTION_POINTER_UP reassigns index 0) can't promote/move under the wrong one.
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
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
    var onDoubleTap: (() -> Unit)?
        get() = gestureCore.onDoubleTap
        set(value) { gestureCore.onDoubleTap = value }

    /**
     * When false, the home gesture detection (swipe-up / long-press / double-tap) is
     * bypassed entirely and touches dispatch normally to children. Set false during
     * wallpaper edit mode so pinch/pan reaches the wallpaper view instead of being
     * detected + consumed by the gesture core (guarding only the callback bodies
     * isn't enough — the core still intercepts the stream).
     */
    var gesturesEnabled: Boolean = true

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastX = ev.x
        lastY = ev.y
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) activePointerId = ev.getPointerId(0)
        if (!gesturesEnabled) return super.dispatchTouchEvent(ev)
        if (dragController.isDragging) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val i = ev.findPointerIndex(activePointerId)
                    if (i >= 0) dragController.onMove(ev.getX(i).toInt(), ev.getY(i).toInt())
                }
                MotionEvent.ACTION_UP -> dragController.onDrop(ev.x.toInt(), ev.y.toInt())
                // The dragging finger lifted while others remain down → settle the drop at
                // its last position; a secondary finger lifting is ignored (drag continues).
                MotionEvent.ACTION_POINTER_UP -> if (ev.getPointerId(ev.actionIndex) == activePointerId) {
                    val i = ev.findPointerIndex(activePointerId)
                    dragController.onDrop(ev.getX(i).toInt(), ev.getY(i).toInt())
                }
                MotionEvent.ACTION_CANCEL -> dragController.onCancel()
            }
            return true
        }
        // Armed (menu shown, deciding drag-vs-menu): own the stream until the arming
        // finger moves (→ promote to drag) or lifts (→ keep the menu). Track that finger
        // by id so a second finger can't drive the decision.
        if (armedPayload != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val i = ev.findPointerIndex(activePointerId)
                    if (i >= 0) {
                        val dx = ev.getX(i) - armX
                        val dy = ev.getY(i) - armY
                        if (dx * dx + dy * dy > touchSlop * touchSlop) {
                            promoteArmedDrag(ev.getX(i).toInt(), ev.getY(i).toInt())
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> disarm()
                // Arming finger lifted (others still down) → keep-the-menu, same as UP.
                MotionEvent.ACTION_POINTER_UP -> if (ev.getPointerId(ev.actionIndex) == activePointerId) disarm()
            }
            return true
        }
        return gestureCore.dispatch(ev) { super.dispatchTouchEvent(it) }
    }

    /**
     * Arm a long-press: cancel the pressing child's gesture, remember the payload,
     * and let [onArm] show the context menu. The stream is now owned here until the
     * finger moves (promote) or lifts (keep menu). Call from the source's long-press.
     */
    fun armDrag(payload: DragPayload, source: View) {
        val cancel = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, lastX, lastY, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
        armedPayload = payload
        armedSource = source
        armX = lastX
        armY = lastY
        onArm?.invoke(payload, source)
    }

    private fun promoteArmedDrag(x: Int, y: Int) {
        val payload = armedPayload ?: return
        val source = armedSource ?: return
        armedPayload = null
        armedSource = null
        onArmedPromote?.invoke(payload)
        dragController.startDrag(payload, source, x, y)
    }

    private fun disarm() {
        armedPayload = null
        armedSource = null
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

    override fun addDragView(source: View, x: Int, y: Int) {
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

    override fun moveDragView(x: Int, y: Int) {
        val view = dragView ?: return
        // Centre on the finger using the known source size — the ImageView isn't
        // laid out yet on the first call, so its own width/height would be 0.
        view.translationX = (x - dragWidth / 2).toFloat()
        view.translationY = (y - dragHeight / 2).toFloat()
    }

    override fun removeDragView() {
        dragView?.let(::removeView)
        dragView = null
        dragSource?.visibility = VISIBLE
        dragSource = null
    }
}
