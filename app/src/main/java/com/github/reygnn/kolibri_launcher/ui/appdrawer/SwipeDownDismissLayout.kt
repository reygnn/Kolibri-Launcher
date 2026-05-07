package com.github.reygnn.kolibri_launcher.ui.appdrawer

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.constraintlayout.widget.ConstraintLayout
import com.github.reygnn.kolibri_launcher.ui.home.SwipeGestureAnalyzer
import com.github.reygnn.kolibri_launcher.ui.util.GestureThresholds

/**
 * Container that detects a decisive downward swipe anywhere within its
 * children and invokes [onSwipeDown], cancelling any in-progress scroll
 * in nested scrolling children (RecyclerView, ScrollView, ...).
 *
 * WHY THIS CLASS EXISTS — the "RecyclerView eats my gesture" problem:
 *
 * RecyclerView calls requestDisallowInterceptTouchEvent(true) on its
 * parent the moment it detects vertical movement past touchSlop. From
 * that moment on, onInterceptTouchEvent on the parent is NEVER called
 * for the rest of the gesture. So every "obvious" approach —
 * GestureDetector on the parent, OnTouchListener on the parent,
 * onInterceptTouchEvent override — silently fails mid-scroll.
 *
 * dispatchTouchEvent is the escape hatch: it's called on the parent
 * unconditionally, regardless of disallowIntercept flags. We mirror
 * the gesture detection here. Once a clear swipe-down is recognised,
 * we synthesize an ACTION_CANCEL down through the children so the
 * RecyclerView stops mid-scroll cleanly, then invoke the callback.
 *
 * VELOCITY-BASED, NOT SCROLL-POSITION-BASED:
 *
 * The detector triggers on velocity, not on whether the list can scroll
 * up. This produces uniform behavior at top / middle / bottom:
 *  - Slow downward drag → never dismisses; either scrolls the list (if
 *    content above) or does nothing (if at top).
 *  - Fast downward flick → always dismisses, regardless of scroll
 *    position.
 *
 * Three constants act as the UX-tuning knobs (see field KDoc).
 */
class SwipeDownDismissLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    // ===========================================
    // PUBLIC API
    // ===========================================

    /**
     * Invoked once per gesture when a decisive downward swipe is detected.
     * The callback owns whatever "dismiss" means in context (popBackStack,
     * viewModel notification, animations, ...).
     */
    var onSwipeDown: (() -> Unit)? = null

    // ===========================================
    // INTERNAL ANALYZER
    // ===========================================

    /**
     * Shares [SwipeGestureAnalyzer] with
     * [com.github.reygnn.kolibri_launcher.ui.home.HomeGestureLayout] so
     * the three-predicate "decisive flick" decision (distance + velocity
     * + axis-dominance) is one source of truth. Calibration comes from
     * [GestureThresholds] — same rationale as the home wrapper. We only
     * react to the [SwipeGestureAnalyzer.SwipeResult.DOWN] result here
     * (the layout exists for one direction); the other results are
     * structurally impossible after a UP/LEFT/RIGHT gesture in an
     * AppDrawer context but treated as no-ops anyway.
     */
    private val analyzer = SwipeGestureAnalyzer(
        distanceThreshold = (
            ViewConfiguration.get(context).scaledTouchSlop *
                GestureThresholds.TOUCH_SLOP_DISTANCE_MULTIPLIER
            ).toFloat(),
        velocityThreshold = GestureThresholds.VELOCITY_PX_PER_MS,
        dominanceFactor = GestureThresholds.DOMINANCE_FACTOR,
    )

    // ===========================================
    // GESTURE STATE (per-touch)
    // ===========================================

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var triggered = false

    // ===========================================
    // TOUCH DISPATCH
    // ===========================================

    /**
     * Touch interception for the gesture this class exists to detect.
     *
     * Contract — three rules a future maintainer must keep:
     *
     *  1. **Read-only inspection until trigger.** ACTION_DOWN snapshots
     *     start coordinates and time; ACTION_MOVE evaluates the gesture
     *     against the three thresholds (distance, velocity, vertical
     *     dominance) but does NOT consume the event yet. Children
     *     (RecyclerView et al.) receive every event normally via
     *     `super.dispatchTouchEvent` and can scroll, click, long-press as
     *     usual. This is what makes "slow drag still scrolls the list"
     *     work — the parent stays out of the way until the gesture is
     *     unmistakable.
     *
     *  2. **One-shot trigger.** Once the three predicates align, we set
     *     `triggered = true`, fire ACTION_CANCEL down to children
     *     (so the RecyclerView stops mid-scroll instead of keeping its
     *     fling), invoke the callback, and consume every remaining event
     *     in the gesture. The flag resets only on the next ACTION_DOWN.
     *     Without the one-shot guard, a held finger past the threshold
     *     would re-fire the dismiss callback every frame.
     *
     *  3. **Never replace this with `onInterceptTouchEvent` or an
     *     `OnTouchListener`.** RecyclerView calls
     *     `requestDisallowInterceptTouchEvent(true)` on us as soon as
     *     it claims the gesture (past touchSlop in the vertical axis),
     *     which silently disables both. `dispatchTouchEvent` is the only
     *     entry point that fires unconditionally on the parent — see the
     *     class KDoc for the full backstory. The "obvious" simplification
     *     is the bug.
     *
     * Returns `true` for every event after a trigger to keep ownership of
     * the gesture (otherwise children would resume mid-fling). Returns
     * whatever `super` returns otherwise — i.e. children decide.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = ev.eventTime
                triggered = false
            }

            MotionEvent.ACTION_MOVE -> if (!triggered) {
                val dx = ev.x - downX
                val dy = ev.y - downY
                val dt = (ev.eventTime - downTime).coerceAtLeast(1L)
                val vx = dx / dt
                val vy = dy / dt

                if (analyzer.analyze(dx, dy, vx, vy) ==
                    SwipeGestureAnalyzer.SwipeResult.DOWN
                ) {
                    triggered = true
                    cancelChildGesture(ev)
                    onSwipeDown?.invoke()
                    return true
                }
            }
        }
        return if (triggered) true else super.dispatchTouchEvent(ev)
    }

    /**
     * Synthesize ACTION_CANCEL so any child currently consuming the
     * gesture (typically the RecyclerView mid-scroll) releases it
     * cleanly. Without this the list would keep its scroll inertia
     * and the drawer would dismiss with a half-finished scroll
     * animation playing underneath.
     */
    private fun cancelChildGesture(source: MotionEvent) {
        val cancel = MotionEvent.obtain(source).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }
}
