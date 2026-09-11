package com.github.reygnn.launcher.common.ui.gesture

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

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
) : FrameLayout(context, attrs, defStyleAttr) {

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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        core.dispatch(ev) { super.dispatchTouchEvent(it) }
}
