package com.github.reygnn.kolibri_launcher.ui.appdrawer

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.NestedScrollingParent3
import com.github.reygnn.launcher.common.ui.gesture.DragToDismissCore
import com.github.reygnn.launcher.common.ui.gesture.GestureDispatchCore

/**
 * App-drawer container that detects a decisive downward swipe anywhere
 * within its children and invokes [onSwipeDown], cancelling any in-progress
 * scroll in nested scrolling children. Thin ConstraintLayout host over the
 * shared [GestureDispatchCore]; see that class for the full rationale
 * (RecyclerView disabling parent intercept mid-scroll, velocity-based
 * trigger, ACTION_CANCEL on fire).
 *
 * The top notification-shade exclusion band is disabled here
 * (`topExclusionPx = 0f`): a downward swipe to dismiss the drawer is
 * intentional at any y, including the top edge.
 */
class SwipeDownDismissLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr), NestedScrollingParent3 {

    private val core = GestureDispatchCore(this).apply { topExclusionPx = 0f }

    var onSwipeDown: (() -> Unit)?
        get() = core.onSwipeDown
        set(value) { core.onSwipeDown = value }

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
