package com.github.reygnn.kolibri_launcher.ui.home

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.constraintlayout.widget.ConstraintLayout

/**
 * Container that detects the five home-screen gestures (four
 * directional swipes, double-tap, long-press) anywhere within its
 * children and forwards them to per-gesture nullable callbacks.
 *
 * Same architectural pattern as [com.github.reygnn.kolibri_launcher.ui.appdrawer.SwipeDownDismissLayout]
 * (the proven AppDrawer variant). Directional swipes go through
 * [dispatchTouchEvent] because nested scrollable children — here a
 * [android.widget.ScrollView] for the favorites list — call
 * `requestDisallowInterceptTouchEvent(true)` mid-gesture, which
 * silently disables `onInterceptTouchEvent` and `OnTouchListener` on
 * the parent. `dispatchTouchEvent` is the only entry point that
 * fires unconditionally on the parent. Tap-based gestures (double-tap
 * and long-press) come from an embedded [GestureDetector] that runs
 * in parallel — taps stay below `touchSlop`, so scroll never claims
 * them and the GestureDetector path works.
 *
 * VELOCITY-BASED, NOT SCROLL-POSITION-BASED:
 *
 * The detector triggers on velocity, not on whether the list can
 * scroll. Same uniform behavior at top / middle / bottom: slow drags
 * scroll the favorites list, fast flicks always fire the gesture.
 *
 * GATING VIA NULLABLE CALLBACKS:
 *
 * Each callback is null by default. The host fragment wires the
 * gestures it cares about and can null individual callbacks at
 * runtime. The wallpaper-edit-mode case is the motivating example:
 * the four swipe callbacks plus [onDoubleTap] are nulled out while
 * the mode is active, but [onLongPress] stays wired (it's how the
 * user exits the mode). A swipe-result that maps to a null callback
 * is treated as IGNORED — the analyzer fires, the wrapper just
 * doesn't dispatch.
 */
class HomeGestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {

    // ===========================================
    // PUBLIC API — per-gesture nullable callbacks
    // ===========================================

    var onSwipeUp: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null
    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    // ===========================================
    // TUNING CONSTANTS
    // ===========================================
    //
    // Mirroring SwipeDownDismissLayout's empirically validated set —
    // those values are the only ones proven to discriminate fast
    // swipes from slow drags on a ScrollView axis without the user
    // perceiving lag or false triggers. Per-direction tuning is a
    // possible follow-up if real-device feel is uneven; start shared.

    /** Higher → user must drag further before a swipe counts. */
    private val minSwipeDistancePx: Float =
        (ViewConfiguration.get(context).scaledTouchSlop * 4).toFloat()

    /** Higher → only fast flicks fire; slow drags can never trigger. */
    private val minVelocityPxPerMs = 1.2f

    /** Higher → gesture must be more strictly axis-aligned. */
    private val dominanceFactor = 1.5f

    // ===========================================
    // INTERNAL ANALYZER
    // ===========================================

    /**
     * Reuses [SwipeGestureAnalyzer] so the axis-dominance + threshold
     * logic stays one source of truth across call sites. Velocity is
     * fed in **px/ms** — the unit naturally produced by `MotionEvent`
     * delta-over-elapsed-time computation. The analyzer is
     * unit-agnostic; the only requirement is that the velocity input
     * and [minVelocityPxPerMs] use the same unit.
     */
    private val analyzer = SwipeGestureAnalyzer(
        distanceThreshold = minSwipeDistancePx,
        velocityThreshold = minVelocityPxPerMs,
        dominanceFactor = dominanceFactor,
    )

    // ===========================================
    // GESTURE STATE (per-touch)
    // ===========================================

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var triggered = false

    /**
     * Whether a descendant view returned `true` from its
     * `onTouchEvent` for the current gesture's ACTION_DOWN. Used to
     * suppress the wrapper's own [tapDetector] when a clickable
     * child (e.g. a favorite button) has its own long-press / click
     * pipeline — otherwise both detectors fire in parallel and the
     * user sees TWO dialogs (the favorite's app-context-menu plus
     * the wrapper's customization-options dialog) on a single
     * long-press.
     */
    private var childClaimedDown = false

    // ===========================================
    // EMBEDDED TAP DETECTOR (double-tap + long-press)
    // ===========================================

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTap?.invoke()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                onLongPress?.invoke()
            }
        },
    )

    // ===========================================
    // TOUCH DISPATCH
    // ===========================================

    /**
     * Touch interception for the five gestures this class detects.
     *
     * Contract — three rules a future maintainer must keep
     * (transcribed verbatim from
     * [com.github.reygnn.kolibri_launcher.ui.appdrawer.SwipeDownDismissLayout]
     * because the same constraints apply):
     *
     *  1. **Read-only inspection until trigger.** ACTION_DOWN
     *     snapshots start coordinates and time; ACTION_MOVE evaluates
     *     the gesture against the analyzer's thresholds (distance,
     *     velocity, dominance) but does NOT consume the event yet.
     *     Children (the ScrollView) receive every event normally via
     *     `super.dispatchTouchEvent` and can scroll, click, long-press
     *     as usual. This is what makes "slow drag still scrolls the
     *     list" work — the parent stays out of the way until the
     *     gesture is unmistakable.
     *
     *  2. **One-shot trigger.** Once the analyzer returns a non-IGNORED
     *     result with a non-null callback, [triggered] flips to true,
     *     ACTION_CANCEL is sent down to children (so the ScrollView
     *     stops mid-scroll instead of keeping its fling), the callback
     *     fires, and every remaining event in the gesture is consumed.
     *     The flag resets only on the next ACTION_DOWN. Without the
     *     one-shot guard, a held finger past threshold would re-fire
     *     every frame.
     *
     *  3. **Never replace this with `onInterceptTouchEvent` or an
     *     `OnTouchListener`.** ScrollView calls
     *     `requestDisallowInterceptTouchEvent(true)` as soon as it
     *     claims the gesture (past touchSlop in the vertical axis),
     *     which silently disables both. `dispatchTouchEvent` is the
     *     only entry point that fires unconditionally on the parent.
     *     The "obvious" simplification is the bug.
     *
     * The embedded [tapDetector] is fed the event unconditionally at
     * the top of the method. It's read-only and reentrancy-safe;
     * feeding it after a directional trigger has already fired is
     * harmless (the upcoming ACTION_CANCEL also flows through it via
     * [cancelChildGesture] and aborts any in-progress tap tracking
     * cleanly). The mutual exclusion between fast swipes and
     * long-press is temporal, not contractual: a fast swipe completes
     * well before the ~500 ms long-press timer fires; a held finger
     * never produces enough velocity to trip the swipe analyzer.
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

                val callback: (() -> Unit)? =
                    when (analyzer.analyze(dx, dy, vx, vy)) {
                        SwipeGestureAnalyzer.SwipeResult.UP -> onSwipeUp
                        SwipeGestureAnalyzer.SwipeResult.DOWN -> onSwipeDown
                        SwipeGestureAnalyzer.SwipeResult.TOWARDS_LEFT -> onSwipeLeft
                        SwipeGestureAnalyzer.SwipeResult.TOWARDS_RIGHT -> onSwipeRight
                        SwipeGestureAnalyzer.SwipeResult.IGNORED -> null
                    }

                if (callback != null) {
                    triggered = true
                    cancelChildGesture(ev)
                    callback.invoke()
                    return true
                }
            }
        }

        // Always run the children's dispatch so they get every event
        // (clicks on favorites, ScrollView's own drag handling, etc.).
        // The return value is what the *parent* (the wallpaperContainer
        // FrameLayout) sees; once we own the gesture the children's
        // result is irrelevant to dispatch routing.
        val consumedBySuper = super.dispatchTouchEvent(ev)

        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            childClaimedDown = consumedBySuper
        }

        // The tap detector only sees events that no descendant has
        // claimed. A clickable child (favorite button) has its own
        // long-press / click pipeline — running ours in parallel
        // would double-fire (the favorite's app-context-menu plus the
        // wrapper's customization-options dialog on a single
        // long-press). For touches in regions with no clickable
        // descendant (empty space below the favorites list), the
        // wrapper's tap detector remains the sole long-press /
        // double-tap surface.
        if (!childClaimedDown) {
            tapDetector.onTouchEvent(ev)
        }

        // ACTION_DOWN claim — even if no descendant returned true,
        // we MUST tell the grandparent "yes, this branch wants the
        // gesture". Without that claim, the FrameLayout treats this
        // branch as rejecting the touch and routes every later
        // ACTION_MOVE / ACTION_UP to a different child or swallows
        // them — `dispatchTouchEvent` then never fires again for
        // the rest of the gesture and our analyzer cannot decide.
        // The empty space below a short favorites list has no
        // clickable descendant — without this claim, the wrapper
        // would only ever see one event per gesture there.
        // SwipeDownDismissLayout in the AppDrawer does NOT need the
        // same workaround because its child is a RecyclerView that
        // claims DOWN unconditionally; that's an accident of its
        // contents, not a property of the dispatchTouchEvent pattern.
        return when {
            triggered -> true
            ev.actionMasked == MotionEvent.ACTION_DOWN -> true
            else -> consumedBySuper
        }
    }

    /**
     * Synthesize ACTION_CANCEL so any child currently consuming the
     * gesture (typically the ScrollView mid-scroll) releases it
     * cleanly. Without this the list would keep its scroll inertia
     * and the home-screen gesture would fire while a scroll animation
     * still plays underneath. The cancel is also fed to [tapDetector]
     * so any in-progress long-press / double-tap tracking aborts in
     * the same step.
     */
    private fun cancelChildGesture(source: MotionEvent) {
        val cancel = MotionEvent.obtain(source).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        tapDetector.onTouchEvent(cancel)
        cancel.recycle()
    }
}
