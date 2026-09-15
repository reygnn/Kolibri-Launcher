package com.github.reygnn.launcher.common.ui.gesture

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.NestedScrollingParent3

/**
 * FrameLayout that detects four directional swipes, double-tap and
 * long-press anywhere within its children and forwards them to per-gesture
 * nullable callbacks. All logic lives in [GestureDispatchCore]; this is a
 * thin FrameLayout-based host (the base class other wrappers, e.g. Kolibri's
 * ConstraintLayout-based ones, differ in — the gesture behaviour is
 * identical because they share the core).
 *
 * Callbacks are null by default; the host wires the ones it wants and may
 * null them at runtime to gate gestures. A swipe whose result maps to a
 * null callback is ignored.
 */
class GestureFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), NestedScrollingParent3 {

    private val core = GestureDispatchCore(this)

    var onSwipeUp: (() -> Unit)?
        get() = core.onSwipeUp
        set(value) { core.onSwipeUp = value }
    var onSwipeDown: (() -> Unit)?
        get() = core.onSwipeDown
        set(value) { core.onSwipeDown = value }
    var onSwipeLeft: (() -> Unit)?
        get() = core.onSwipeLeft
        set(value) { core.onSwipeLeft = value }
    var onSwipeRight: (() -> Unit)?
        get() = core.onSwipeRight
        set(value) { core.onSwipeRight = value }
    var onDoubleTap: (() -> Unit)?
        get() = core.onDoubleTap
        set(value) { core.onDoubleTap = value }
    var onLongPress: (() -> Unit)?
        get() = core.onLongPress
        set(value) { core.onLongPress = value }

    /** See [GestureDispatchCore.topExclusionPx]. Set 0f to disable (e.g. drawer dismiss). */
    var topExclusionPx: Float
        get() = core.topExclusionPx
        set(value) { core.topExclusionPx = value }

    private val drag = DragToDismissCore(this)

    /** View that follows the finger during drag-to-dismiss — must be the view
     *  the host animates on hide (the overlay container). Null disables it. */
    var dragTarget: View?
        get() = drag.dragTarget
        set(value) { drag.dragTarget = value }
    var onDismissDrag: (() -> Unit)?
        get() = drag.onDismiss
        set(value) { drag.onDismiss = value }
    var onDragProgress: ((Float) -> Unit)?
        get() = drag.onDragProgress
        set(value) { drag.onDragProgress = value }

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean =
        drag.onStartNestedScroll(axes, type)

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) =
        drag.onNestedScrollAccepted()

    override fun onStopNestedScroll(target: View, type: Int) = drag.onStopNestedScroll(type)

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) =
        drag.onNestedPreScroll(dy, consumed, type)

    override fun onNestedScroll(
        target: View, dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int, type: Int, consumed: IntArray,
    ) = drag.onNestedScroll(target, dyUnconsumed, type, consumed)

    override fun onNestedScroll(
        target: View, dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int, type: Int,
    ) = drag.onNestedScroll(target, dyUnconsumed, type, null)

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean =
        drag.onNestedPreFling(target, velocityY)

    override fun onDetachedFromWindow() {
        drag.cancel()
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        core.dispatch(ev) { super.dispatchTouchEvent(it) }
}
