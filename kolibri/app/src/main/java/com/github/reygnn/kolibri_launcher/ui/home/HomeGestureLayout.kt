package com.github.reygnn.kolibri_launcher.ui.home

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.constraintlayout.widget.ConstraintLayout
import com.github.reygnn.launcher.common.ui.gesture.GestureDispatchCore

/**
 * Home-screen gesture container: four directional swipes, double-tap and
 * long-press anywhere within its children, forwarded to per-gesture nullable
 * callbacks. The wrapper is a thin ConstraintLayout host — all detection
 * logic lives in the shared [GestureDispatchCore] (see its KDoc for the
 * dispatchTouchEvent-not-onInterceptTouchEvent rationale and the one-shot /
 * ACTION_CANCEL contract). The ConstraintLayout base is kept because
 * fragment_home.xml constrains this layout's children.
 *
 * Gating stays caller-side: HomeFragment nulls the swipe + double-tap
 * callbacks in wallpaper-edit mode while keeping onLongPress wired as the
 * exit gesture. A swipe whose result maps to a null callback is ignored.
 * The top notification-shade exclusion band is on by default (see
 * [GestureDispatchCore.topExclusionPx]).
 */
class HomeGestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {

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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        core.dispatch(ev) { super.dispatchTouchEvent(it) }
}
