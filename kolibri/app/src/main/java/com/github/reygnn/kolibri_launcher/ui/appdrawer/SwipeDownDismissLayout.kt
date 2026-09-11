package com.github.reygnn.kolibri_launcher.ui.appdrawer

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.constraintlayout.widget.ConstraintLayout
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
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val core = GestureDispatchCore(this).apply { topExclusionPx = 0f }

    var onSwipeDown: (() -> Unit)?
        get() = core.onSwipeDown
        set(value) { core.onSwipeDown = value }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        core.dispatch(ev) { super.dispatchTouchEvent(it) }
}
