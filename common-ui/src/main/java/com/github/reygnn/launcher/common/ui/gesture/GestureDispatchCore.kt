package com.github.reygnn.launcher.common.ui.gesture

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup

/**
 * Base-agnostic gesture state machine, shared by every `dispatchTouchEvent`
 * wrapper. This is the battle-tested logic extracted verbatim from the
 * Kolibri wrappers (HomeGestureLayout + SwipeDownDismissLayout); the only
 * change is that it takes its [host] ViewGroup as a parameter instead of
 * *being* the ViewGroup, so a ConstraintLayout-based wrapper (Kolibri) and
 * a FrameLayout-based wrapper (Nyx) can both delegate to one implementation.
 *
 * WHY dispatchTouchEvent AND NOT onInterceptTouchEvent / OnTouchListener:
 * RecyclerView / ScrollView / ViewPager2 call
 * `requestDisallowInterceptTouchEvent(true)` the moment they claim vertical
 * movement past touchSlop; from then on `onInterceptTouchEvent` and any
 * `OnTouchListener` on the parent are NEVER called for the rest of the
 * gesture. `dispatchTouchEvent` is the only parent entry point that fires
 * unconditionally. The "obvious" simplification is the bug.
 *
 * Contract (three rules a maintainer must keep):
 *  1. Read-only inspection until trigger. DOWN snapshots start
 *     coordinates + time; MOVE evaluates against the analyzer's thresholds
 *     but does NOT consume — children receive every event via
 *     [dispatch]'s `superDispatch` and scroll/click/long-press as usual.
 *     This is what makes "slow drag still scrolls the list" work.
 *  2. One-shot trigger. Once the analyzer returns a non-IGNORED result
 *     with a non-null callback, [triggered] flips, ACTION_CANCEL is sent
 *     down (so the scrolling child stops instead of keeping its fling),
 *     the callback fires, and the rest of the gesture is consumed. Reset
 *     only on the next DOWN.
 *  3. Feed the tap detector unconditionally but null-gated: it consumes a
 *     double-tap only if [onDoubleTap] is wired, fires [onLongPress] only
 *     if wired, and is suppressed over descendants that run their own
 *     touch pipeline (see [childClaimedDown]).
 *
 * The wrapper hooks in with a single line:
 * `override fun dispatchTouchEvent(ev) = core.dispatch(ev) { super.dispatchTouchEvent(it) }`
 */
class GestureDispatchCore(private val host: ViewGroup) {

    // ===========================================
    // PUBLIC API — per-gesture nullable callbacks
    // ===========================================

    var onSwipeUp: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null
    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    /**
     * Top exclusion band (px) reserved for the system notification shade;
     * a swipe-DOWN whose ACTION_DOWN lands above this y-offset is ceded to
     * the system. Defaults to [GestureThresholds.TOP_NOTIFICATION_EXCLUSION_DP].
     * Set to 0f to disable (e.g. an app-drawer dismiss surface, where the
     * downward swipe is intentional at any y).
     */
    var topExclusionPx: Float =
        host.resources.displayMetrics.density * GestureThresholds.TOP_NOTIFICATION_EXCLUSION_DP

    // ===========================================
    // INTERNAL ANALYZER
    // ===========================================

    private val analyzer = SwipeGestureAnalyzer(
        distanceThreshold = (
            ViewConfiguration.get(host.context).scaledTouchSlop *
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
    private var doubleTapFired = false
    private var childClaimedDown = false

    // ===========================================
    // EMBEDDED TAP DETECTOR (double-tap + long-press)
    // ===========================================

    private val tapDetector = GestureDetector(
        host.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // A confirmed, wired double tap always consumes the gesture.
                // The null-check is the only exception: with no listener we
                // must NOT consume — a long-press exit gesture has to stay free.
                val listener = onDoubleTap ?: return false
                doubleTapFired = true
                listener.invoke()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (doubleTapFired) return
                onLongPress?.invoke()
            }
        },
    )

    // ===========================================
    // TOUCH DISPATCH
    // ===========================================

    /**
     * Call from the host's `dispatchTouchEvent`, passing a lambda that
     * forwards to `super.dispatchTouchEvent`. Returns what the host should
     * return to *its* parent.
     */
    fun dispatch(ev: MotionEvent, superDispatch: (MotionEvent) -> Boolean): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = ev.eventTime
                triggered = false
                doubleTapFired = false
                childClaimedDown = hasOwnTouchPipelineDescendantAt(ev.x, ev.y)
            }

            // `!doubleTapFired`: a flick off the second tap of a double tap
            // must not ALSO dispatch a swipe.
            MotionEvent.ACTION_MOVE -> if (!triggered && !doubleTapFired) {
                val dx = ev.x - downX
                val dy = ev.y - downY
                val dt = (ev.eventTime - downTime).coerceAtLeast(1L)
                val vx = dx / dt
                val vy = dy / dt

                val callback: (() -> Unit)? =
                    when (analyzer.analyze(dx, dy, vx, vy)) {
                        SwipeGestureAnalyzer.SwipeResult.UP -> onSwipeUp
                        // A swipe-down starting inside the top exclusion band
                        // is ceded to the system notification shade.
                        SwipeGestureAnalyzer.SwipeResult.DOWN ->
                            if (downY < topExclusionPx) null else onSwipeDown
                        SwipeGestureAnalyzer.SwipeResult.TOWARDS_LEFT -> onSwipeLeft
                        SwipeGestureAnalyzer.SwipeResult.TOWARDS_RIGHT -> onSwipeRight
                        SwipeGestureAnalyzer.SwipeResult.IGNORED -> null
                    }

                if (callback != null) {
                    triggered = true
                    cancelChildGesture(ev, superDispatch)
                    callback.invoke()
                    return true
                }
            }
        }

        // Always run the children's dispatch so they get every event.
        val consumedBySuper = superDispatch(ev)

        // The tap detector only sees events when no descendant with its own
        // touch pipeline (clickable / long-clickable) lives at the touch
        // position — otherwise the child's own action and ours double-fire.
        if (!childClaimedDown) {
            tapDetector.onTouchEvent(ev)
        }

        // ACTION_DOWN claim: even if no descendant consumed it, we must tell
        // the grandparent "this branch wants the gesture", or every later
        // MOVE/UP is routed elsewhere and dispatch never fires again — fatal
        // over empty space (e.g. below a short list) with no clickable child.
        return when {
            triggered -> true
            ev.actionMasked == MotionEvent.ACTION_DOWN -> true
            else -> consumedBySuper
        }
    }

    /**
     * Synthesize ACTION_CANCEL so any child currently consuming the gesture
     * (typically a RecyclerView mid-scroll) releases it cleanly; without it
     * the list keeps its scroll inertia while the gesture action plays. The
     * cancel is also fed to [tapDetector] so any in-progress tap tracking
     * aborts in the same step.
     */
    private fun cancelChildGesture(source: MotionEvent, superDispatch: (MotionEvent) -> Boolean) {
        val cancel = MotionEvent.obtain(source).apply { action = MotionEvent.ACTION_CANCEL }
        superDispatch(cancel)
        tapDetector.onTouchEvent(cancel)
        cancel.recycle()
    }

    /**
     * Walks the visible view tree from [host] downward, mirroring
     * `ViewGroup.dispatchTouchEvent`'s hit-testing for axis-aligned,
     * untransformed children, and returns true if any view on the path to
     * the deepest descendant at (`rootX`, `rootY`) runs its own touch
     * pipeline — `isLongClickable` or `hasOnClickListeners()`.
     *
     * Accuracy gap: does NOT account for runtime matrix transforms
     * (rotation/scale/translation). If a transformed view is added inside a
     * gesture wrapper, audit this method.
     */
    private fun hasOwnTouchPipelineDescendantAt(rootX: Float, rootY: Float): Boolean {
        var view: View = host
        var x = rootX
        var y = rootY
        while (view is ViewGroup) {
            if (view !== host && view.hasOwnTouchPipeline()) return true
            var hitChild: View? = null
            var hitX = 0f
            var hitY = 0f
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                val cx = x - child.left + view.scrollX
                val cy = y - child.top + view.scrollY
                if (cx >= 0f && cx < child.width.toFloat() &&
                    cy >= 0f && cy < child.height.toFloat()) {
                    hitChild = child
                    hitX = cx
                    hitY = cy
                    break
                }
            }
            if (hitChild == null) return false
            view = hitChild
            x = hitX
            y = hitY
        }
        return view.hasOwnTouchPipeline()
    }

    private fun View.hasOwnTouchPipeline(): Boolean =
        isLongClickable || hasOnClickListeners()
}
